package su.nightexpress.excellentcrates.opening;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.opening.Opening;
import su.nightexpress.excellentcrates.api.opening.OpeningProvider;
import su.nightexpress.excellentcrates.api.opening.ProviderLoader;
import su.nightexpress.excellentcrates.api.opening.ProviderSupplier;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.opening.world.WorldOpening;
import su.nightexpress.excellentcrates.opening.world.provider.DummyProvider;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.lib.folialib.wrapper.task.WrappedTask;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.util.FileUtil;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OpeningManager extends AbstractManager<CratesPlugin> {

    private final Map<String, OpeningProvider> providerByIdMap;
    private final Map<UUID, Opening>           openingByPlayerMap;
    private final Map<UUID, WrappedTask>       openingTaskByPlayerMap;

    private final DummyProvider dummyProvider;

    public OpeningManager(@NotNull CratesPlugin plugin) {
        super(plugin);
        this.providerByIdMap = new HashMap<>();
        this.openingByPlayerMap = new ConcurrentHashMap<>();
        this.openingTaskByPlayerMap = new ConcurrentHashMap<>();
        this.dummyProvider = new DummyProvider(plugin);
    }

    @Override
    protected void onLoad() {
        this.loadDefaults();
        this.loadProviders();

        this.addListener(new OpeningListener(this.plugin, this));

        this.startAsyncOpeningTicker();
    }

    @Override
    protected void onShutdown() {
        new ArrayList<>(this.openingTaskByPlayerMap.values()).forEach(WrappedTask::cancel);
        this.openingTaskByPlayerMap.clear();
        this.getOpenings().forEach(Opening::stop);
        this.providerByIdMap.clear();
        this.openingByPlayerMap.clear();
    }

    @NotNull
    private String getDirectoryPath(@NotNull String dirName) {
        return this.plugin.getDataFolder() + dirName;
    }

    private void loadDefaults() {
        File dir = new File(this.getDirectoryPath(Config.DIR_OPENINGS));
        if (dir.exists()) return;

        this.loadProvider("csgo", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupCSGO);
        this.loadProvider("enclosing", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupEnclosing);
        this.loadProvider("mystery", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupMystery);
        this.loadProvider("roulette", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupRoulette);
        this.loadProvider("storm", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupStorm);

        this.loadProvider("simple_roll", Config.DIR_OPENINGS_SIMPLE_ROLL, OpeningUtils::createSimpleRoll);

        this.loadProvider("selective_1", Config.DIR_OPENINGS_SELECTABLE, OpeningUtils::createSelectableSingle);
        this.loadProvider("selective_3", Config.DIR_OPENINGS_SELECTABLE, OpeningUtils::createSelectableTriple);
    }

    public void loadProviders() {
        // Load providers stored in the openings directory by native or externally added loaders.
        for (ProviderLoader loader : ProviderRegistry.getLoaders()) {
            this.loadProviders(loader);
        }

        // Load externally added independend opening providers.
        for (OpeningProvider provider : ProviderRegistry.getProviders()) {
            this.loadProvider(provider);
        }

        this.plugin.info("Loaded " + this.providerByIdMap.size() + " crate openings.");
    }

    public void loadProviders(@NotNull ProviderLoader loader) {
        String dirName = loader.getDirectory();
        ProviderSupplier supplier = loader.getSupplier();

        for (File file : FileUtil.getConfigFiles(this.getDirectoryPath(dirName))) {
            this.loadProvider(file, supplier);
        }
    }

    public void loadProvider(@NotNull String id, @NotNull String dirName, @NotNull ProviderSupplier supplier) {
        File file = new File(this.getDirectoryPath(dirName), FileConfig.withExtension(id));
        this.loadProvider(file, supplier);
    }

    public void loadProvider(@NotNull File file, @NotNull ProviderSupplier supplier) {
        FileConfig config = new FileConfig(file);
        String name = FileConfig.getName(file);

        OpeningProvider provider = supplier.supply(this.plugin, name);
        provider.load(config);
        config.saveChanges();

        this.loadProvider(provider);
    }

    public void loadProvider(@NotNull OpeningProvider provider) {
        this.providerByIdMap.put(provider.getId(), provider);
    }

    @NotNull
    public Map<String, OpeningProvider> getProviderByIdMap() {
        return this.providerByIdMap;
    }

    @NotNull
    public Set<OpeningProvider> getProviders() {
        return new HashSet<>(this.providerByIdMap.values());
    }

    @NotNull
    public Set<String> getProviderIds() {
        return new HashSet<>(this.providerByIdMap.keySet());
    }

    @Nullable
    public OpeningProvider getProviderById(@NotNull String id) {
        return this.providerByIdMap.get(id.toLowerCase());
    }

    @NotNull
    public Map<UUID, Opening> getOpeningByPlayerIdMap() {
        return this.openingByPlayerMap;
    }

    @NotNull
    public Set<Opening> getOpenings() {
        return new HashSet<>(this.openingByPlayerMap.values());
    }

    @Nullable
    public Opening getOpening(@NotNull Player player) {
        return this.openingByPlayerMap.get(player.getUniqueId());
    }

    private void startAsyncOpeningTicker() {
        this.plugin.getFoliaScheduler().runTimerAsync(() -> {
            if (this.openingByPlayerMap.isEmpty()) return;
            this.processOpeningsAsync();
        }, 0L, 1L);
    }

    private void processOpeningsAsync() {
        Set<Opening> openings = new HashSet<>(this.openingByPlayerMap.values());

        for (Opening opening : openings) {
            if (!opening.isRunning()) continue;

            if (!(opening instanceof AsyncProcessable asyncOpening)) continue;

            try {
                this.processOpeningAsync(opening, asyncOpening);
            } catch (Exception e) {
                this.plugin.error("Error processing opening for player " + opening.getPlayer().getName() + ": " + e.getMessage());
                e.printStackTrace();
                this.plugin.getFoliaScheduler().runAtEntity(opening.getPlayer(), () -> {
                    this.stopOpening(opening.getPlayer());
                });
            }
        }
    }

    private void processOpeningAsync(Opening opening, AsyncProcessable asyncOpening) {
        AsyncOpeningUpdate update = asyncOpening.processAsync();

        if (update != null && update.hasUpdates()) {
            this.plugin.getFoliaScheduler().runAtEntity(opening.getPlayer(), () -> {
                try {
                    update.applyToMainThread();
                } catch (Exception e) {
                    this.plugin.error("Error applying opening update for player " + opening.getPlayer().getName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }
    }

    public void tickOpenings() {
        List<Opening> openingsCopy = new ArrayList<>(this.getOpenings());
        openingsCopy.forEach(this::tickOpening);
    }

    private void tickOpening(@NotNull Opening opening) {
        Player player = opening.getPlayer();
        UUID playerId = player.getUniqueId();

        if (this.openingByPlayerMap.get(playerId) != opening) {
            this.cancelOpeningTask(playerId);
            return;
        }

        try {
            opening.tick();
        } catch (Exception e) {
            this.plugin.error("Error ticking opening for player " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            this.stopOpening(player);
            return;
        }

        if (!opening.isRunning()) {
            this.cancelOpeningTask(playerId);
        }
    }

    private void startOpeningTicker(@NotNull Opening opening) {
        UUID playerId = opening.getPlayer().getUniqueId();
        this.cancelOpeningTask(playerId);

        WrappedTask task = this.createOpeningTask(opening);
        this.openingTaskByPlayerMap.put(playerId, task);
    }

    @NotNull
    private WrappedTask createOpeningTask(@NotNull Opening opening) {
        Location location = this.getOpeningTickLocation(opening);
        if (location != null) {
            return this.plugin.getFoliaScheduler().getFoliaLib().getScheduler().runAtLocationTimer(location, () -> this.tickOpening(opening), 0L, 1L);
        }

        return this.plugin.getFoliaScheduler().getFoliaLib().getScheduler().runAtEntityTimer(
            opening.getPlayer(),
            () -> this.tickOpening(opening),
            () -> this.removeOpening(opening.getPlayer()),
            0L,
            1L
        );
    }

    @Nullable
    private Location getOpeningTickLocation(@NotNull Opening opening) {
        if (!(opening instanceof WorldOpening)) return null;

        WorldPos blockPos = opening.getSource().getBlockPos();
        if (blockPos == null || blockPos.isEmpty()) return null;

        return blockPos.toLocation();
    }

    private void cancelOpeningTask(@NotNull UUID playerId) {
        WrappedTask task = this.openingTaskByPlayerMap.remove(playerId);
        if (task == null || task.isCancelled()) return;

        task.cancel();
    }

    public boolean isOpening(@NotNull Player player) {
        return this.getOpening(player) != null;
    }

    public void stopOpening(@NotNull Player player) {
        Opening opening = this.removeOpening(player);
        if (opening == null) return;

        opening.stop();

        this.plugin.getRedisSyncManager().ifPresent(sync ->
            sync.publishOpeningStateCleanup(player.getUniqueId(), "player_quit")
        );
    }

    @Nullable
    public Opening removeOpening(@NotNull Player player) {
        this.cancelOpeningTask(player.getUniqueId());
        return this.openingByPlayerMap.remove(player.getUniqueId());
    }

    public boolean isOpeningAvailable(@NotNull Player player) {
        return !this.isOpening(player);
    }

    @NotNull
    public Opening createOpening(@NotNull Player player, @NotNull CrateSource source, @Nullable Cost cost) {
        Crate crate = source.getCrate();
        OpeningProvider provider = null;

        if (crate.isOpeningEnabled()) {
            provider = this.getProviderById(crate.getOpeningId());
        }
        if (provider == null) provider = this.dummyProvider;

        return provider.createOpening(player, source, cost);
    }

    public void startOpening(@NotNull Player player, @NotNull Opening opening, boolean instaRoll) {
        if (this.openingByPlayerMap.putIfAbsent(player.getUniqueId(), opening) != null) return;

        opening.start(); // Start ticking

        if (instaRoll) opening.instaRoll();
        if (opening.isRunning()) this.startOpeningTicker(opening);
    }
}
