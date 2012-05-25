package org.bukkit.craftbukkit;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.apache.commons.lang.Validate;

import org.bukkit.craftbukkit.entity.*;
import org.bukkit.craftbukkit.metadata.BlockMetadataStore;
import org.bukkit.entity.*;
import org.bukkit.entity.Entity;

import net.minecraft.server.*;

import org.bukkit.entity.Arrow;
import org.bukkit.Effect;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.world.SpawnChangeEvent;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Boat;
import org.bukkit.Chunk;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.bukkit.BlockChangeDelegate;
import org.bukkit.Bukkit;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.Difficulty;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.plugin.messaging.StandardMessenger;

public class CraftWorld implements World {
    private final WeakReference<WorldServer> world;
    private Environment environment;
    private final CraftServer server = (CraftServer) Bukkit.getServer();
    private final ChunkGenerator generator;
    private final List<BlockPopulator> populators = new ArrayList<BlockPopulator>();
    private final BlockMetadataStore blockMetadata = new BlockMetadataStore(this);
    private int monsterSpawn = -1;
    private int animalSpawn = -1;
    private int waterAnimalSpawn = -1;

    private static final Random rand = new Random();

    public CraftWorld(WorldServer world, ChunkGenerator gen, Environment env) {
        this.world = new WeakReference(world);
        this.generator = gen;

        environment = env;
    }

    public Block getBlockAt(int x, int y, int z) {
        return getChunkAt(x >> 4, z >> 4).getBlock(x & 0xF, y & 0xFF, z & 0xF);
    }

    public int getBlockTypeIdAt(int x, int y, int z) {
        return getHandle().getTypeId(x, y, z);
    }

    public int getHighestBlockYAt(int x, int z) {
        if (!isChunkLoaded(x >> 4, z >> 4)) {
            loadChunk(x >> 4, z >> 4);
        }

        return getHandle().getHighestBlockYAt(x, z);
    }

    public Location getSpawnLocation() {
        ChunkCoordinates spawn = getHandle().getSpawn();
        return new Location(this, spawn.x, spawn.y, spawn.z);
    }

    public boolean setSpawnLocation(int x, int y, int z) {
        try {
            Location previousLocation = getSpawnLocation();
            getHandle().worldData.setSpawn(x, y, z);

            // Notify anyone who's listening.
            SpawnChangeEvent event = new SpawnChangeEvent(this, previousLocation);
            server.getPluginManager().callEvent(event);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Chunk getChunkAt(int x, int z) {
        return this.getHandle().chunkProviderServer.getChunkAt(x, z).bukkitChunk;
    }

    public Chunk getChunkAt(Block block) {
        return getChunkAt(block.getX() >> 4, block.getZ() >> 4);
    }

    public boolean isChunkLoaded(int x, int z) {
        return getHandle().chunkProviderServer.isChunkLoaded(x, z);
    }

    public Chunk[] getLoadedChunks() {
        Object[] chunks = getHandle().chunkProviderServer.chunks.values().toArray();
        org.bukkit.Chunk[] craftChunks = new CraftChunk[chunks.length];

        for (int i = 0; i < chunks.length; i++) {
            net.minecraft.server.Chunk chunk = (net.minecraft.server.Chunk) chunks[i];
            craftChunks[i] = chunk.bukkitChunk;
        }

        return craftChunks;
    }

    public void loadChunk(int x, int z) {
        loadChunk(x, z, true);
    }

    public boolean unloadChunk(Chunk chunk) {
        return unloadChunk(chunk.getX(), chunk.getZ());
    }

    public boolean unloadChunk(int x, int z) {
        return unloadChunk(x, z, true);
    }

    public boolean unloadChunk(int x, int z, boolean save) {
        return unloadChunk(x, z, save, false);
    }

    public boolean unloadChunkRequest(int x, int z) {
        return unloadChunkRequest(x, z, true);
    }

    public boolean unloadChunkRequest(int x, int z, boolean safe) {
        if (safe && isChunkInUse(x, z)) {
            return false;
        }

        getHandle().chunkProviderServer.queueUnload(x, z);

        return true;
    }

    public boolean unloadChunk(int x, int z, boolean save, boolean safe) {
        if (safe && isChunkInUse(x, z)) {
            return false;
        }

        net.minecraft.server.Chunk chunk = getHandle().chunkProviderServer.getOrCreateChunk(x, z);

        if (save && !(chunk instanceof EmptyChunk)) {
            chunk.removeEntities();
            getHandle().chunkProviderServer.saveChunk(chunk);
            getHandle().chunkProviderServer.saveChunkNOP(chunk);
        }

        getHandle().chunkProviderServer.unloadQueue.remove(x, z);
        getHandle().chunkProviderServer.chunks.remove(x, z);
        getHandle().chunkProviderServer.chunkList.remove(chunk);

        return true;
    }

    public boolean regenerateChunk(int x, int z) {
        unloadChunk(x, z, false, false);

        getHandle().chunkProviderServer.unloadQueue.remove(x, z);

        net.minecraft.server.Chunk chunk = null;

        if (getHandle().chunkProviderServer.chunkProvider == null) {
            chunk = getHandle().chunkProviderServer.emptyChunk;
        } else {
            chunk = getHandle().chunkProviderServer.chunkProvider.getOrCreateChunk(x, z);
        }

        chunkLoadPostProcess(chunk, x, z);

        refreshChunk(x, z);

        return chunk != null;
    }

    public boolean refreshChunk(int x, int z) {
        if (!isChunkLoaded(x, z)) {
            return false;
        }

        int px = x << 4;
        int pz = z << 4;

        // If there are more than 64 updates to a chunk at once, it carries out the update as a cuboid
        // This flags 64 blocks along the bottom for update and then flags a block at the opposite corner at the top
        // The cuboid that contains these 65 blocks covers the entire chunk
        // The server will compress the chunk and send it to all clients

        for (int xx = px; xx < (px + 16); xx++) {
            getHandle().notify(xx, 0, pz);
            getHandle().notify(xx, 1, pz);
            getHandle().notify(xx, 2, pz);
            getHandle().notify(xx, 3, pz);
        }
        getHandle().notify(px, 255, pz + 15);

        return true;
    }

    public boolean isChunkInUse(int x, int z) {
        Player[] players = server.getOnlinePlayers();

        for (Player player : players) {
            Location loc = player.getLocation();
            if (loc.getWorld() != getHandle().chunkProviderServer.world.getWorld()) {
                continue;
            }

            // If the chunk is within 256 blocks of a player, refuse to accept the unload request
            // This is larger than the distance of loaded chunks that actually surround a player
            // The player is the center of a 21x21 chunk grid, so the edge is 10 chunks (160 blocks) away from the player
            if (Math.abs(loc.getBlockX() - (x << 4)) <= 256 && Math.abs(loc.getBlockZ() - (z << 4)) <= 256) {
                return true;
            }
        }
        return false;
    }

    public boolean loadChunk(int x, int z, boolean generate) {
        if (generate) {
            // Use the default variant of loadChunk when generate == true.
            return getHandle().chunkProviderServer.getChunkAt(x, z) != null;
        }

        getHandle().chunkProviderServer.unloadQueue.remove(x, z);
        net.minecraft.server.Chunk chunk = (net.minecraft.server.Chunk) getHandle().chunkProviderServer.chunks.get(x, z);

        if (chunk == null) {
            chunk = getHandle().chunkProviderServer.loadChunk(x, z);

            chunkLoadPostProcess(chunk, x, z);
        }
        return chunk != null;
    }

    @SuppressWarnings("unchecked")
    private void chunkLoadPostProcess(net.minecraft.server.Chunk chunk, int x, int z) {
        if (chunk != null) {
            getHandle().chunkProviderServer.chunks.put(x, z, chunk);
            getHandle().chunkProviderServer.chunkList.add(chunk);

            chunk.loadNOP();
            chunk.addEntities();

            if (!chunk.done && getHandle().chunkProviderServer.isChunkLoaded(x + 1, z + 1) && getHandle().chunkProviderServer.isChunkLoaded(x, z + 1) && getHandle().chunkProviderServer.isChunkLoaded(x + 1, z)) {
                getHandle().chunkProviderServer.getChunkAt(getHandle().chunkProviderServer, x, z);
            }

            if (getHandle().chunkProviderServer.isChunkLoaded(x - 1, z) && !getHandle().chunkProviderServer.getOrCreateChunk(x - 1, z).done && getHandle().chunkProviderServer.isChunkLoaded(x - 1, z + 1) && getHandle().chunkProviderServer.isChunkLoaded(x, z + 1) && getHandle().chunkProviderServer.isChunkLoaded(x - 1, z)) {
                getHandle().chunkProviderServer.getChunkAt(getHandle().chunkProviderServer, x - 1, z);
            }

            if (getHandle().chunkProviderServer.isChunkLoaded(x, z - 1) && !getHandle().chunkProviderServer.getOrCreateChunk(x, z - 1).done && getHandle().chunkProviderServer.isChunkLoaded(x + 1, z - 1) && getHandle().chunkProviderServer.isChunkLoaded(x, z - 1) && getHandle().chunkProviderServer.isChunkLoaded(x + 1, z)) {
                getHandle().chunkProviderServer.getChunkAt(getHandle().chunkProviderServer, x, z - 1);
            }

            if (getHandle().chunkProviderServer.isChunkLoaded(x - 1, z - 1) && !getHandle().chunkProviderServer.getOrCreateChunk(x - 1, z - 1).done && getHandle().chunkProviderServer.isChunkLoaded(x - 1, z - 1) && getHandle().chunkProviderServer.isChunkLoaded(x, z - 1) && getHandle().chunkProviderServer.isChunkLoaded(x - 1, z)) {
                getHandle().chunkProviderServer.getChunkAt(getHandle().chunkProviderServer, x - 1, z - 1);
            }
        }
    }

    public boolean isChunkLoaded(Chunk chunk) {
        return isChunkLoaded(chunk.getX(), chunk.getZ());
    }

    public void loadChunk(Chunk chunk) {
        loadChunk(chunk.getX(), chunk.getZ());
        ((CraftChunk) getChunkAt(chunk.getX(), chunk.getZ())).getHandle().bukkitChunk = chunk;
    }

    public WorldServer getHandle() {
        return world.get();
    }

    public org.bukkit.entity.Item dropItem(Location loc, ItemStack item) {
        Validate.notNull(item, "Cannot drop a Null item.");
        Validate.isTrue(item.getTypeId() != 0, "Cannot drop AIR.");
        CraftItemStack clone = new CraftItemStack(item);
        EntityItem entity = new EntityItem(getHandle(), loc.getX(), loc.getY(), loc.getZ(), clone.getHandle());
        entity.pickupDelay = 10;
        getHandle().addEntity(entity);
        // TODO this is inconsistent with how Entity.getBukkitEntity() works.
        // However, this entity is not at the moment backed by a server entity class so it may be left.
        return new CraftItem(getHandle().getServer(), entity);
    }

    public org.bukkit.entity.Item dropItemNaturally(Location loc, ItemStack item) {
        double xs = getHandle().random.nextFloat() * 0.7F + (1.0F - 0.7F) * 0.5D;
        double ys = getHandle().random.nextFloat() * 0.7F + (1.0F - 0.7F) * 0.5D;
        double zs = getHandle().random.nextFloat() * 0.7F + (1.0F - 0.7F) * 0.5D;
        loc = loc.clone();
        loc.setX(loc.getX() + xs);
        loc.setY(loc.getY() + ys);
        loc.setZ(loc.getZ() + zs);
        return dropItem(loc, item);
    }

    public Arrow spawnArrow(Location loc, Vector velocity, float speed, float spread) {
        EntityArrow arrow = new EntityArrow(getHandle());
        arrow.setPositionRotation(loc.getX(), loc.getY(), loc.getZ(), 0, 0);
        getHandle().addEntity(arrow);
        arrow.shoot(velocity.getX(), velocity.getY(), velocity.getZ(), speed, spread);
        return (Arrow) arrow.getBukkitEntity();
    }

    @Deprecated
    public LivingEntity spawnCreature(Location loc, CreatureType creatureType) {
        return spawnCreature(loc, creatureType.toEntityType());
    }

    public LivingEntity spawnCreature(Location loc, EntityType creatureType) {
        Entity result = spawn(loc, creatureType.getEntityClass());

        if (result == null) {
            return null;
        }

        return (LivingEntity) result;
    }

    public LightningStrike strikeLightning(Location loc) {
        EntityWeatherLighting lightning = new EntityWeatherLighting(getHandle(), loc.getX(), loc.getY(), loc.getZ());
        getHandle().strikeLightning(lightning);
        return new CraftLightningStrike(server, lightning);
    }

    public LightningStrike strikeLightningEffect(Location loc) {
        EntityWeatherLighting lightning = new EntityWeatherLighting(getHandle(), loc.getX(), loc.getY(), loc.getZ(), true);
        getHandle().strikeLightning(lightning);
        return new CraftLightningStrike(server, lightning);
    }

    public boolean generateTree(Location loc, TreeType type) {
        return generateTree(loc, type, getHandle());
    }

    public boolean generateTree(Location loc, TreeType type, BlockChangeDelegate delegate) {
        BlockSapling.TreeGenerator gen;
        switch (type) {
        case BIG_TREE:
            gen = new WorldGenBigTree(true);
            break;
        case BIRCH:
            gen = new WorldGenForest(true);
            break;
        case REDWOOD:
            gen = new WorldGenTaiga2(true);
            break;
        case TALL_REDWOOD:
            gen = new WorldGenTaiga1();
            break;
        case JUNGLE:
            gen = new WorldGenMegaTree(true, 10 + rand.nextInt(20), 3, 3);
            break;
        case SMALL_JUNGLE:
            gen = new WorldGenTrees(true, 4 + rand.nextInt(7), 3, 3, false);
            break;
        case JUNGLE_BUSH:
            gen = new WorldGenGroundBush(3, 0);
            break;
        case RED_MUSHROOM:
            gen = new WorldGenHugeMushroom(1);
            break;
        case BROWN_MUSHROOM:
            gen = new WorldGenHugeMushroom(0);
            break;
        case SWAMP:
            gen = new WorldGenSwampTree();
            break;
        case TREE:
        default:
            gen = new WorldGenTrees(true);
            break;
        }

        return gen.generate(delegate, rand, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public TileEntity getTileEntityAt(final int x, final int y, final int z) {
        return getHandle().getTileEntity(x, y, z);
    }

    public String getName() {
        return getHandle().worldData.name;
    }

    @Deprecated
    public long getId() {
        return getHandle().worldData.getSeed();
    }

    public UUID getUID() {
        return getHandle().getUUID();
    }

    @Override
    public String toString() {
        return "CraftWorld{name=" + getName() + '}';
    }

    public long getTime() {
        long time = getFullTime() % 24000;
        if (time < 0) time += 24000;
        return time;
    }

    public void setTime(long time) {
        long margin = (time - getFullTime()) % 24000;
        if (margin < 0) margin += 24000;
        setFullTime(getFullTime() + margin);
    }

    public long getFullTime() {
        return getHandle().getTime();
    }

    public void setFullTime(long time) {
        getHandle().setTime(time);

        // Forces the client to update to the new time immediately
        for (Player p : getPlayers()) {
            CraftPlayer cp = (CraftPlayer) p;
            if (cp.getHandle().netServerHandler == null) continue;

            cp.getHandle().netServerHandler.sendPacket(new Packet4UpdateTime(cp.getHandle().getPlayerTime()));
        }
    }

    public boolean createExplosion(double x, double y, double z, float power) {
        return createExplosion(x, y, z, power, false);
    }

    public boolean createExplosion(double x, double y, double z, float power, boolean setFire) {
        return getHandle().createExplosion(null, x, y, z, power, setFire).wasCanceled ? false : true;
    }

    public boolean createExplosion(Location loc, float power) {
        return createExplosion(loc, power, false);
    }

    public boolean createExplosion(Location loc, float power, boolean setFire) {
        return createExplosion(loc.getX(), loc.getY(), loc.getZ(), power, setFire);
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment env) {
        if (environment != env) {
            environment = env;
            getHandle().worldProvider = WorldProvider.byDimension(environment.getId());
        }
    }

    public Block getBlockAt(Location location) {
        return getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public int getBlockTypeIdAt(Location location) {
        return getBlockTypeIdAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public int getHighestBlockYAt(Location location) {
        return getHighestBlockYAt(location.getBlockX(), location.getBlockZ());
    }

    public Chunk getChunkAt(Location location) {
        return getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public ChunkGenerator getGenerator() {
        return generator;
    }

    public List<BlockPopulator> getPopulators() {
        return populators;
    }

    public Block getHighestBlockAt(int x, int z) {
        return getBlockAt(x, getHighestBlockYAt(x, z), z);
    }

    public Block getHighestBlockAt(Location location) {
        return getHighestBlockAt(location.getBlockX(), location.getBlockZ());
    }

    public Biome getBiome(int x, int z) {
        return CraftBlock.biomeBaseToBiome(this.getHandle().getBiome(x, z));
    }

    public void setBiome(int x, int z, Biome bio) {
        BiomeBase bb = CraftBlock.biomeToBiomeBase(bio);
        if (this.getHandle().isLoaded(x, 0, z)) {
            net.minecraft.server.Chunk chunk = this.getHandle().getChunkAtWorldCoords(x, z);

            if (chunk != null) {
                byte[] biomevals = chunk.l();
                biomevals[((z & 0xF) << 4) | (x & 0xF)] = (byte)bb.id;
            }
        }
    }

    public double getTemperature(int x, int z) {
        return this.getHandle().getBiome(x, z).F;
    }

    public double getHumidity(int x, int z) {
        return this.getHandle().getBiome(x, z).G;
    }

    public List<Entity> getEntities() {
        List<Entity> list = new ArrayList<Entity>();

        for (Object o : getHandle().entityList) {
            if (o instanceof net.minecraft.server.Entity) {
                net.minecraft.server.Entity mcEnt = (net.minecraft.server.Entity) o;
                Entity bukkitEntity = mcEnt.getBukkitEntity();

                // Assuming that bukkitEntity isn't null
                if (bukkitEntity != null) {
                    list.add(bukkitEntity);
                }
            }
        }

        return list;
    }

    public List<LivingEntity> getLivingEntities() {
        List<LivingEntity> list = new ArrayList<LivingEntity>();

        for (Object o : getHandle().entityList) {
            if (o instanceof net.minecraft.server.Entity) {
                net.minecraft.server.Entity mcEnt = (net.minecraft.server.Entity) o;
                Entity bukkitEntity = mcEnt.getBukkitEntity();

                // Assuming that bukkitEntity isn't null
                if (bukkitEntity != null && bukkitEntity instanceof LivingEntity) {
                    list.add((LivingEntity) bukkitEntity);
                }
            }
        }

        return list;
    }

    @SuppressWarnings("unchecked")
    @Deprecated
    public <T extends Entity> Collection<T> getEntitiesByClass(Class<T>... classes) {
        return (Collection<T>)getEntitiesByClasses(classes);
    }

    @SuppressWarnings("unchecked")
    public <T extends Entity> Collection<T> getEntitiesByClass(Class<T> clazz) {
        Collection<T> list = new ArrayList<T>();

        for (Object entity: getHandle().entityList) {
            if (entity instanceof net.minecraft.server.Entity) {
                Entity bukkitEntity = ((net.minecraft.server.Entity) entity).getBukkitEntity();

                if (bukkitEntity == null) {
                    continue;
                }

                Class<?> bukkitClass = bukkitEntity.getClass();

                if (clazz.isAssignableFrom(bukkitClass)) {
                    list.add((T) bukkitEntity);
                }
            }
        }

        return list;
    }

    public Collection<Entity> getEntitiesByClasses(Class<?>... classes) {
        Collection<Entity> list = new ArrayList<Entity>();

        for (Object entity: getHandle().entityList) {
            if (entity instanceof net.minecraft.server.Entity) {
                Entity bukkitEntity = ((net.minecraft.server.Entity) entity).getBukkitEntity();

                if (bukkitEntity == null) {
                    continue;
                }

                Class<?> bukkitClass = bukkitEntity.getClass();

                for (Class<?> clazz : classes) {
                    if (clazz.isAssignableFrom(bukkitClass)) {
                        list.add(bukkitEntity);
                        break;
                    }
                }
            }
        }

        return list;
    }

    public List<Player> getPlayers() {
        List<Player> list = new ArrayList<Player>();

        for (Object o : getHandle().entityList) {
            if (o instanceof net.minecraft.server.Entity) {
                net.minecraft.server.Entity mcEnt = (net.minecraft.server.Entity) o;
                Entity bukkitEntity = mcEnt.getBukkitEntity();

                if ((bukkitEntity != null) && (bukkitEntity instanceof Player)) {
                    list.add((Player) bukkitEntity);
                }
            }
        }

        return list;
    }

    public void save() {
        boolean oldSave = getHandle().savingDisabled;

        getHandle().savingDisabled = false;
        getHandle().save(true, null);

        getHandle().savingDisabled = oldSave;
    }

    public boolean isAutoSave() {
        return !getHandle().savingDisabled;
    }

    public void setAutoSave(boolean value) {
        getHandle().savingDisabled = !value;
    }

    public void setDifficulty(Difficulty difficulty) {
        this.getHandle().difficulty = difficulty.getValue();
    }

    public Difficulty getDifficulty() {
        return Difficulty.getByValue(this.getHandle().difficulty);
    }

    public BlockMetadataStore getBlockMetadata() {
        return blockMetadata;
    }

    public boolean hasStorm() {
        return getHandle().worldData.hasStorm();
    }

    public void setStorm(boolean hasStorm) {
        CraftServer server = getHandle().getServer();

        WeatherChangeEvent weather = new WeatherChangeEvent((org.bukkit.World) this, hasStorm);
        server.getPluginManager().callEvent(weather);
        if (!weather.isCancelled()) {
            getHandle().worldData.setStorm(hasStorm);

            // These numbers are from Minecraft
            if (hasStorm) {
                setWeatherDuration(rand.nextInt(12000) + 12000);
            } else {
                setWeatherDuration(rand.nextInt(168000) + 12000);
            }
        }
    }

    public int getWeatherDuration() {
        return getHandle().worldData.getWeatherDuration();
    }

    public void setWeatherDuration(int duration) {
        getHandle().worldData.setWeatherDuration(duration);
    }

    public boolean isThundering() {
        return hasStorm() && getHandle().worldData.isThundering();
    }

    public void setThundering(boolean thundering) {
        if (thundering && !hasStorm()) setStorm(true);
        CraftServer server = getHandle().getServer();

        ThunderChangeEvent thunder = new ThunderChangeEvent((org.bukkit.World) this, thundering);
        server.getPluginManager().callEvent(thunder);
        if (!thunder.isCancelled()) {
            getHandle().worldData.setThundering(thundering);

            // These numbers are from Minecraft
            if (thundering) {
                setThunderDuration(rand.nextInt(12000) + 3600);
            } else {
                setThunderDuration(rand.nextInt(168000) + 12000);
            }
        }
    }

    public int getThunderDuration() {
        return getHandle().worldData.getThunderDuration();
    }

    public void setThunderDuration(int duration) {
        getHandle().worldData.setThunderDuration(duration);
    }

    public long getSeed() {
        return getHandle().worldData.getSeed();
    }

    public boolean getPVP() {
        return getHandle().pvpMode;
    }

    public void setPVP(boolean pvp) {
        getHandle().pvpMode = pvp;
    }

    public void playEffect(Player player, Effect effect, int data) {
        playEffect(player.getLocation(), effect, data, 0);
    }

    public void playEffect(Location location, Effect effect, int data) {
        playEffect(location, effect, data, 64);
    }

    public <T> void playEffect(Location loc, Effect effect, T data) {
        playEffect(loc, effect, data, 64);
    }

    public <T> void playEffect(Location loc, Effect effect, T data, int radius) {
        if (data != null) {
            Validate.isTrue(data.getClass().equals(effect.getData()), "Wrong kind of data for this effect!");
        } else {
            Validate.isTrue(effect.getData() == null, "Wrong kind of data for this effect!");
        }

        int datavalue = data == null ? 0 : CraftEffect.getDataValue(effect, data);
        playEffect(loc, effect, datavalue, radius);
    }

    public void playEffect(Location location, Effect effect, int data, int radius) {
        int packetData = effect.getId();
        Packet61WorldEvent packet = new Packet61WorldEvent(packetData, location.getBlockX(), location.getBlockY(), location.getBlockZ(), data);
        int distance;
        radius *= radius;

        for (Player player : getPlayers()) {
            if (((CraftPlayer) player).getHandle().netServerHandler == null) continue;

            distance = (int) player.getLocation().distanceSquared(location);
            if (distance <= radius) {
                ((CraftPlayer) player).getHandle().netServerHandler.sendPacket(packet);
            }
        }
    }

    public <T extends Entity> T spawn(Location location, Class<T> clazz) throws IllegalArgumentException {
        return spawn(location, clazz, SpawnReason.CUSTOM);
    }

    @SuppressWarnings("unchecked")
    public <T extends Entity> T spawn(Location location, Class<T> clazz, SpawnReason reason) throws IllegalArgumentException {
        if (location == null || clazz == null) {
            throw new IllegalArgumentException("Location or entity class cannot be null");
        }

        net.minecraft.server.Entity entity = null;

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        float pitch = location.getPitch();
        float yaw = location.getYaw();

        // order is important for some of these
        if (Boat.class.isAssignableFrom(clazz)) {
            entity = new EntityBoat(getHandle(), x, y, z);
        } else if (FallingSand.class.isAssignableFrom(clazz)) {
            entity = new EntityFallingBlock(getHandle(), x, y, z, 0, 0);
        } else if (Projectile.class.isAssignableFrom(clazz)) {
            if (Snowball.class.isAssignableFrom(clazz)) {
                entity = new EntitySnowball(getHandle(), x, y, z);
            } else if (Egg.class.isAssignableFrom(clazz)) {
                entity = new EntityEgg(getHandle(), x, y, z);
            } else if (EnderPearl.class.isAssignableFrom(clazz)) {
                entity = new EntityEnderPearl(getHandle(), x, y, z);
            } else if (Arrow.class.isAssignableFrom(clazz)) {
                entity = new EntityArrow(getHandle());
                entity.setPositionRotation(x, y, z, 0, 0);
            } else if (ThrownExpBottle.class.isAssignableFrom(clazz)) {
                entity = new EntityThrownExpBottle(getHandle());
                entity.setPositionRotation(x, y, z, 0, 0);
            } else if (Fireball.class.isAssignableFrom(clazz)) {
                if (SmallFireball.class.isAssignableFrom(clazz)) {
                    entity = new EntitySmallFireball(getHandle());
                } else {
                    entity = new EntityFireball(getHandle());
                }
                ((EntityFireball) entity).setPositionRotation(x, y, z, yaw, pitch);
                Vector direction = location.getDirection().multiply(10);
                ((EntityFireball) entity).setDirection(direction.getX(), direction.getY(), direction.getZ());
            }
        } else if (Minecart.class.isAssignableFrom(clazz)) {
            if (PoweredMinecart.class.isAssignableFrom(clazz)) {
                entity = new EntityMinecart(getHandle(), x, y, z, CraftMinecart.Type.PoweredMinecart.getId());
            } else if (StorageMinecart.class.isAssignableFrom(clazz)) {
                entity = new EntityMinecart(getHandle(), x, y, z, CraftMinecart.Type.StorageMinecart.getId());
            } else {
                entity = new EntityMinecart(getHandle(), x, y, z, CraftMinecart.Type.Minecart.getId());
            }
        } else if (EnderSignal.class.isAssignableFrom(clazz)) {
            entity = new EntityEnderSignal(getHandle(), x, y, z);
        } else if (EnderCrystal.class.isAssignableFrom(clazz)) {
            entity = new EntityEnderCrystal(getHandle());
            entity.setPositionRotation(x, y, z, 0, 0);
        } else if (LivingEntity.class.isAssignableFrom(clazz)) {
            if (Chicken.class.isAssignableFrom(clazz)) {
                entity = new EntityChicken(getHandle());
            } else if (Cow.class.isAssignableFrom(clazz)) {
                if (MushroomCow.class.isAssignableFrom(clazz)) {
                    entity = new EntityMushroomCow(getHandle());
                } else {
                    entity = new EntityCow(getHandle());
                }
            } else if (Golem.class.isAssignableFrom(clazz)) {
                if (Snowman.class.isAssignableFrom(clazz)) {
                    entity = new EntitySnowman(getHandle());
                } else if (IronGolem.class.isAssignableFrom(clazz)) {
                    entity = new EntityIronGolem(getHandle());
                }
            } else if (Creeper.class.isAssignableFrom(clazz)) {
                entity = new EntityCreeper(getHandle());
            } else if (Ghast.class.isAssignableFrom(clazz)) {
                entity = new EntityGhast(getHandle());
            } else if (Pig.class.isAssignableFrom(clazz)) {
                entity = new EntityPig(getHandle());
            } else if (Player.class.isAssignableFrom(clazz)) {
                // need a net server handler for this one
            } else if (Sheep.class.isAssignableFrom(clazz)) {
                entity = new EntitySheep(getHandle());
            } else if (Skeleton.class.isAssignableFrom(clazz)) {
                entity = new EntitySkeleton(getHandle());
            } else if (Slime.class.isAssignableFrom(clazz)) {
                if (MagmaCube.class.isAssignableFrom(clazz)) {
                    entity = new EntityMagmaCube(getHandle());
                } else {
                    entity = new EntitySlime(getHandle());
                }
            } else if (Spider.class.isAssignableFrom(clazz)) {
                if (CaveSpider.class.isAssignableFrom(clazz)) {
                    entity = new EntityCaveSpider(getHandle());
                } else {
                    entity = new EntitySpider(getHandle());
                }
            } else if (Squid.class.isAssignableFrom(clazz)) {
                entity = new EntitySquid(getHandle());
            } else if (Tameable.class.isAssignableFrom(clazz)) {
                if (Wolf.class.isAssignableFrom(clazz)) {
                    entity = new EntityWolf(getHandle());
                } else if (Ocelot.class.isAssignableFrom(clazz)) {
                    entity = new EntityOcelot(getHandle());
                }
            } else if (PigZombie.class.isAssignableFrom(clazz)) {
                entity = new EntityPigZombie(getHandle());
            } else if (Zombie.class.isAssignableFrom(clazz)) {
                entity = new EntityZombie(getHandle());
            } else if (Giant.class.isAssignableFrom(clazz)) {
                entity = new EntityGiantZombie(getHandle());
            } else if (Silverfish.class.isAssignableFrom(clazz)) {
                entity = new EntitySilverfish(getHandle());
            } else if (Enderman.class.isAssignableFrom(clazz)) {
                entity = new EntityEnderman(getHandle());
            } else if (Blaze.class.isAssignableFrom(clazz)) {
                entity = new EntityBlaze(getHandle());
            } else if (Villager.class.isAssignableFrom(clazz)) {
                entity = new EntityVillager(getHandle());
            } else if (ComplexLivingEntity.class.isAssignableFrom(clazz)) {
                if (EnderDragon.class.isAssignableFrom(clazz)) {
                    entity = new EntityEnderDragon(getHandle());
                }
            }

            if (entity != null) {
                entity.setLocation(x, y, z, pitch, yaw);
            }
        } else if (Painting.class.isAssignableFrom(clazz)) {
            Block block = getBlockAt(location);
            BlockFace face = BlockFace.SELF;
            if (block.getRelative(BlockFace.EAST).getTypeId() == 0) {
                face = BlockFace.EAST;
            } else if (block.getRelative(BlockFace.NORTH).getTypeId() == 0) {
                face = BlockFace.NORTH;
            } else if (block.getRelative(BlockFace.WEST).getTypeId() == 0) {
                face = BlockFace.WEST;
            } else if (block.getRelative(BlockFace.SOUTH).getTypeId() == 0) {
                face = BlockFace.SOUTH;
            }
            int dir;
            switch (face) {
            case EAST:
            default:
                dir = 0;
                break;
            case NORTH:
                dir = 1;
                break;
            case WEST:
                dir = 2;
                break;
            case SOUTH:
                dir = 3;
                ;
                break;
            }
            entity = new EntityPainting(getHandle(), (int) x, (int) y, (int) z, dir);
            if (!((EntityPainting) entity).survives()) {
                entity = null;
            }
        } else if (TNTPrimed.class.isAssignableFrom(clazz)) {
            entity = new EntityTNTPrimed(getHandle(), x, y, z);
        } else if (ExperienceOrb.class.isAssignableFrom(clazz)) {
            entity = new EntityExperienceOrb(getHandle(), x, y, z, 0);
        } else if (Weather.class.isAssignableFrom(clazz)) {
            // not sure what this can do
            entity = new EntityWeatherLighting(getHandle(), x, y, z);
        } else if (LightningStrike.class.isAssignableFrom(clazz)) {
            // what is this, I don't even
        } else if (Fish.class.isAssignableFrom(clazz)) {
            // this is not a fish, it's a bobber, and it's probably useless
            entity = new EntityFishingHook(getHandle());
            entity.setLocation(x, y, z, pitch, yaw);
        }

        if (entity != null) {
            getHandle().addEntity(entity, reason);
            return (T) entity.getBukkitEntity();
        }

        throw new IllegalArgumentException("Cannot spawn an entity for " + clazz.getName());
    }

    public ChunkSnapshot getEmptyChunkSnapshot(int x, int z, boolean includeBiome, boolean includeBiomeTempRain) {
        return CraftChunk.getEmptyChunkSnapshot(x, z, this, includeBiome, includeBiomeTempRain);
    }

    public void setSpawnFlags(boolean allowMonsters, boolean allowAnimals) {
        getHandle().setSpawnFlags(allowMonsters, allowAnimals);
    }

    public boolean getAllowAnimals() {
        return getHandle().allowAnimals;
    }

    public boolean getAllowMonsters() {
        return getHandle().allowMonsters;
    }

    public int getMaxHeight() {
        return getHandle().getHeight();
    }

    public int getSeaLevel() {
        return 64;
    }

    public boolean getKeepSpawnInMemory() {
        return getHandle().keepSpawnInMemory;
    }

    public void setKeepSpawnInMemory(boolean keepLoaded) {
        getHandle().keepSpawnInMemory = keepLoaded;
        // Grab the worlds spawn chunk
        ChunkCoordinates chunkcoordinates = this.getHandle().getSpawn();
        int chunkCoordX = chunkcoordinates.x >> 4;
        int chunkCoordZ = chunkcoordinates.z >> 4;
        // Cycle through the 25x25 Chunks around it to load/unload the chunks.
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                if (keepLoaded) {
                    loadChunk(chunkCoordX + x, chunkCoordZ + z);
                } else {
                    if (isChunkLoaded(chunkCoordX + x, chunkCoordZ + z)) {
                        if (this.getHandle().getChunkAt(chunkCoordX + x, chunkCoordZ + z) instanceof EmptyChunk) {
                            unloadChunk(chunkCoordX + x, chunkCoordZ + z, false);
                        } else {
                            unloadChunk(chunkCoordX + x, chunkCoordZ + z);
                        }
                    }
                }
            }
        }
    }

    @Override
    public int hashCode() {
        return getUID().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        final CraftWorld other = (CraftWorld) obj;

        return this.getUID() == other.getUID();
    }

    public File getWorldFolder() {
        return ((WorldNBTStorage) getHandle().getDataManager()).getDirectory();
    }

    public void explodeBlock(Block block, float yield) {
        // First of all, don't explode fire
        if (block.getType().equals(org.bukkit.Material.AIR) || block.getType().equals(org.bukkit.Material.FIRE)) {
            return;
        }
        int blockId = block.getTypeId();
        int blockX = block.getX();
        int blockY = block.getY();
        int blockZ = block.getZ();
        // following code is lifted from Explosion.a(boolean), and modified
        net.minecraft.server.Block.byId[blockId].dropNaturally(this.getHandle(), blockX, blockY, blockZ, block.getData(), yield, 0);
        block.setType(org.bukkit.Material.AIR);
        // not sure what this does, seems to have something to do with the 'base' material of a block.
        // For example, WOODEN_STAIRS does something with WOOD in this method
        net.minecraft.server.Block.byId[blockId].wasExploded(this.getHandle(), blockX, blockY, blockZ);
    }

    public void sendPluginMessage(Plugin source, String channel, byte[] message) {
        StandardMessenger.validatePluginMessage(server.getMessenger(), source, channel, message);

        for (Player player : getPlayers()) {
            player.sendPluginMessage(source, channel, message);
        }
    }

    public Set<String> getListeningPluginChannels() {
        Set<String> result = new HashSet<String>();

        for (Player player : getPlayers()) {
            result.addAll(player.getListeningPluginChannels());
        }

        return result;
    }

    public org.bukkit.WorldType getWorldType() {
        return org.bukkit.WorldType.getByName(getHandle().getWorldData().getType().name());
    }

    public boolean canGenerateStructures() {
        return getHandle().getWorldData().shouldGenerateMapFeatures();
    }

    public long getTicksPerAnimalSpawns() {
        return getHandle().ticksPerAnimalSpawns;
    }

    public void setTicksPerAnimalSpawns(int ticksPerAnimalSpawns) {
        getHandle().ticksPerAnimalSpawns = ticksPerAnimalSpawns;
    }

    public long getTicksPerMonsterSpawns() {
        return getHandle().ticksPerMonsterSpawns;
    }

    public void setTicksPerMonsterSpawns(int ticksPerMonsterSpawns) {
        getHandle().ticksPerMonsterSpawns = ticksPerMonsterSpawns;
    }

    public void setMetadata(String metadataKey, MetadataValue newMetadataValue) {
        server.getWorldMetadata().setMetadata(this, metadataKey, newMetadataValue);
    }

    public List<MetadataValue> getMetadata(String metadataKey) {
        return server.getWorldMetadata().getMetadata(this, metadataKey);
    }

    public boolean hasMetadata(String metadataKey) {
        return server.getWorldMetadata().hasMetadata(this, metadataKey);
    }

    public void removeMetadata(String metadataKey, Plugin owningPlugin) {
        server.getWorldMetadata().removeMetadata(this, metadataKey, owningPlugin);
    }

    public int getMonsterSpawnLimit() {
        if (monsterSpawn < 0) {
            return server.getMonsterSpawnLimit();
        }

        return monsterSpawn;
    }

    public void setMonsterSpawnLimit(int limit) {
        monsterSpawn = limit;
    }

    public int getAnimalSpawnLimit() {
        if (animalSpawn < 0) {
            return server.getAnimalSpawnLimit();
        }

        return animalSpawn;
    }

    public void setAnimalSpawnLimit(int limit) {
        animalSpawn = limit;
    }

    public int getWaterAnimalSpawnLimit() {
        if (waterAnimalSpawn < 0) {
            return server.getWaterAnimalSpawnLimit();
        }

        return waterAnimalSpawn;
    }

    public void setWaterAnimalSpawnLimit(int limit) {
        waterAnimalSpawn = limit;
    }
}
