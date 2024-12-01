package me.pawix25;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class Snowball_hole_filler implements ModInitializer {
    private static final Logger logger = LoggerFactory.getLogger(Snowball_hole_filler.class);
    private final Set<SnowballEntity> activeSnowballs = new HashSet<>();

    // Statistics tracking
    private final Map<UUID, Integer> playerBlocksFilled = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> playerBlockTypes = new HashMap<>();

    // Undo and Redo system
    private static class BlockChange {
        final BlockPos pos;
        final BlockState previousState;
        final BlockState newState; // Added for redo functionality
        final long timestamp;
        final UUID playerUUID;

        BlockChange(BlockPos pos, BlockState previousState, BlockState newState, UUID playerUUID) {
            this.pos = pos;
            this.previousState = previousState;
            this.newState = newState;
            this.timestamp = System.currentTimeMillis();
            this.playerUUID = playerUUID;
        }
    }

    private static final int MAX_UNDO_HISTORY = 50;
    private final Map<UUID, LinkedList<List<BlockChange>>> playerUndoHistory = new HashMap<>();
    private final Map<UUID, LinkedList<List<BlockChange>>> playerRedoHistory = new HashMap<>(); // Added for redo

    // Configuration class
    public static class SnowballConfig {
        private static int radius = 3;
        private static int maxDepth = 3;
        private static boolean enabled = true;

        public static int getRadius() { return radius; }
        public static void setRadius(int value) { radius = value; }
        public static int getMaxDepth() { return maxDepth; }
        public static void setMaxDepth(int value) { maxDepth = value; }
        public static boolean isEnabled() { return enabled; }
        public static void setEnabled(boolean value) { enabled = value; }
    }

    @Override
    public void onInitialize() {
        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("snowballfiller")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.literal("radius")
                            .then(CommandManager.argument("value", IntegerArgumentType.integer(1, 10))
                                    .executes(context -> {
                                        int newRadius = IntegerArgumentType.getInteger(context, "value");
                                        SnowballConfig.setRadius(newRadius);
                                        context.getSource().sendMessage(Text.literal("Snowball filler radius set to: " + newRadius));
                                        return 1;
                                    })))
                    .then(CommandManager.literal("depth")
                            .then(CommandManager.argument("value", IntegerArgumentType.integer(1, 10))
                                    .executes(context -> {
                                        int newDepth = IntegerArgumentType.getInteger(context, "value");
                                        SnowballConfig.setMaxDepth(newDepth);
                                        context.getSource().sendMessage(Text.literal("Snowball filler depth set to: " + newDepth));
                                        return 1;
                                    })))
                    .then(CommandManager.literal("toggle")
                            .executes(context -> {
                                boolean newState = !SnowballConfig.isEnabled();
                                SnowballConfig.setEnabled(newState);
                                context.getSource().sendMessage(Text.literal("Snowball filler " + (newState ? "enabled" : "disabled")));
                                return 1;
                            }))
                    .then(CommandManager.literal("status")
                            .executes(context -> {
                                context.getSource().sendMessage(Text.literal(String.format(
                                        "Snowball Filler Status:\nEnabled: %s\nRadius: %d\nMax Depth: %d",
                                        SnowballConfig.isEnabled(),
                                        SnowballConfig.getRadius(),
                                        SnowballConfig.getMaxDepth()
                                )));
                                return 1;
                            }))
                    .then(CommandManager.literal("undo")
                            .executes(context -> {
                                if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                                    return undoLastOperation(player);
                                }
                                return 0;
                            }))
                    .then(CommandManager.literal("redo") // Added redo command
                            .executes(context -> {
                                if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                                    return redoLastOperation(player);
                                }
                                return 0;
                            }))
                    .then(CommandManager.literal("stats")
                            .executes(context -> {
                                if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                                    // Get player stats
                                    int totalBlocks = playerBlocksFilled.getOrDefault(player.getUuid(), 0);
                                    Map<String, Integer> blockTypes = playerBlockTypes.getOrDefault(player.getUuid(), new HashMap<>());

                                    // Send total blocks filled
                                    player.sendMessage(Text.literal("Total blocks filled: " + totalBlocks));

                                    // Send most used blocks
                                    if (!blockTypes.isEmpty()) {
                                        player.sendMessage(Text.literal("Most used blocks:"));
                                        blockTypes.entrySet().stream()
                                                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                                                .limit(5)
                                                .forEach(entry -> {
                                                    player.sendMessage(Text.literal("- " + entry.getKey() + ": " + entry.getValue()));
                                                });
                                    }
                                    return 1;
                                }
                                return 0;
                            })));
        });

        // Register snowball throw event
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player.getStackInHand(hand).getItem() == Items.SNOWBALL) {
                Vec3d pos = player.getPos();
                logger.info("Player {} threw a snowball at x:{}, y:{}, z:{}",
                        player.getName().getString(),
                        pos.x, pos.y, pos.z);

                if (world instanceof ServerWorld) {
                    ServerTickEvents.START_SERVER_TICK.register(server -> {
                        server.getWorlds().forEach(serverWorld -> {
                            Box box = new Box(pos.add(-5, -5, -5), pos.add(5, 5, 5));
                            serverWorld.getEntitiesByClass(SnowballEntity.class, box, entity -> true).forEach(snowball -> {
                                if (!activeSnowballs.contains(snowball)) {
                                    activeSnowballs.add(snowball);
                                }
                            });
                        });
                    });
                }
            }
            return TypedActionResult.pass(player.getStackInHand(hand));
        });

        // Register snowball landing event
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            server.getWorlds().forEach(serverWorld -> {
                new HashSet<>(activeSnowballs).forEach(snowball -> {
                    if (!snowball.isAlive()) {
                        Vec3d pos = snowball.getPos();
                        logger.info("Snowball landed at x:{}, y:{}, z:{}",
                                pos.x, pos.y, pos.z);

                        BlockPos blockPos = new BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
                        if (shouldFill(blockPos, serverWorld)) {
                            UUID playerUUID = snowball.getOwner() instanceof ServerPlayerEntity player ?
                                    player.getUuid() : null;
                            fillHolesAround(serverWorld, blockPos, playerUUID);
                        }

                        activeSnowballs.remove(snowball);
                    }
                });
            });
        });
    }

    private int undoLastOperation(ServerPlayerEntity player) {
        UUID playerUUID = player.getUuid();
        LinkedList<List<BlockChange>> playerHistory = playerUndoHistory.get(playerUUID);

        if (playerHistory == null || playerHistory.isEmpty()) {
            player.sendMessage(Text.literal("No changes to undo!"), false);
            return 0;
        }

        List<BlockChange> lastOperation = playerHistory.removeFirst();
        ServerWorld world = player.getServerWorld();
        int blocksRestored = 0;

        for (BlockChange change : lastOperation) {
            world.setBlockState(change.pos, change.previousState);
            blocksRestored++;
        }

        playerRedoHistory.computeIfAbsent(playerUUID, k -> new LinkedList<>()).addFirst(lastOperation);

        player.sendMessage(Text.literal("Undid last operation. Restored " + blocksRestored + " blocks."), false);
        return 1;
    }

    private int redoLastOperation(ServerPlayerEntity player) { // Added redo method
        UUID playerUUID = player.getUuid();
        LinkedList<List<BlockChange>> playerRedo = playerRedoHistory.get(playerUUID);

        if (playerRedo == null || playerRedo.isEmpty()) {
            player.sendMessage(Text.literal("No changes to redo!"), false);
            return 0;
        }

        List<BlockChange> lastRedo = playerRedo.removeFirst();
        ServerWorld world = player.getServerWorld();
        int blocksRestored = 0;

        for (BlockChange change : lastRedo) {
            world.setBlockState(change.pos, change.newState);
            blocksRestored++;
        }

        playerUndoHistory.computeIfAbsent(playerUUID, k -> new LinkedList<>()).addFirst(lastRedo);

        player.sendMessage(Text.literal("Redid last operation. Restored " + blocksRestored + " blocks."), false);
        return 1;
    }

    private boolean shouldFill(BlockPos pos, ServerWorld world) {
        if (!SnowballConfig.isEnabled()) return false;

        BlockPos[] adjacentPositions = {
                pos.north(), pos.south(), pos.east(), pos.west(),
                pos.north().east(), pos.north().west(), pos.south().east(), pos.south().west(),
                pos.up(), pos.down()
        };

        int solidBlocks = 0;
        for (BlockPos adjacent : adjacentPositions) {
            BlockState state = world.getBlockState(adjacent);
            if (!state.isAir() && state.getBlock() != Blocks.WATER) {
                solidBlocks++;
            }
        }

        // Require at least 3 solid adjacent blocks for more natural filling
        return solidBlocks >= 3;
    }

    private boolean shouldFillAtHeight(ServerWorld world, BlockPos pos) {
        int maxHeightDifference = 2;
        BlockPos[] neighbors = {pos.north(), pos.south(), pos.east(), pos.west()};

        int currentHeight = pos.getY();
        int averageHeight = 0;
        int validNeighbors = 0;

        for (BlockPos neighbor : neighbors) {
            // Find the highest solid block
            BlockPos checkPos = neighbor;
            while (world.getBlockState(checkPos).isAir() && checkPos.getY() > world.getBottomY()) {
                checkPos = checkPos.down();
            }

            if (!world.getBlockState(checkPos).isAir()) {
                averageHeight += checkPos.getY();
                validNeighbors++;
            }
        }

        if (validNeighbors == 0) return false;

        averageHeight /= validNeighbors;
        return Math.abs(currentHeight - averageHeight) <= maxHeightDifference;
    }

    private void fillHolesAround(ServerWorld world, BlockPos pos, UUID playerUUID) {
        if (!SnowballConfig.isEnabled()) return;

        int radius = SnowballConfig.getRadius();
        int maxDepth = SnowballConfig.getMaxDepth();
        HashMap<BlockState, Integer> blockFrequency = new HashMap<>();
        List<BlockChange> operationChanges = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int yOffset = -maxDepth; yOffset <= 1; yOffset++) {
                    BlockPos checkPos = pos.add(x, yOffset, z).toImmutable();
                    BlockState currentState = world.getBlockState(checkPos);

                    if ((currentState.isAir() || currentState.getBlock() == Blocks.WATER) &&
                            shouldFillAtHeight(world, checkPos)) {
                        BlockState blockToFillWith = findBestFillingBlock(world, checkPos, blockFrequency);
                        if (blockToFillWith != null) {
                            // Store the change for undo and redo
                            operationChanges.add(new BlockChange(checkPos, currentState, blockToFillWith, playerUUID));

                            world.setBlockState(checkPos, blockToFillWith);

                            // Update statistics
                            if (playerUUID != null) {
                                // Update total blocks filled
                                playerBlocksFilled.merge(playerUUID, 1, Integer::sum);

                                // Update block type statistics
                                String blockName = blockToFillWith.getBlock().toString();
                                playerBlockTypes.computeIfAbsent(playerUUID, k -> new HashMap<>())
                                        .merge(blockName, 1, Integer::sum);
                            }

                            world.playSound(null, checkPos, blockToFillWith.getSoundGroup().getPlaceSound(),
                                    net.minecraft.sound.SoundCategory.BLOCKS, 1.0F, 1.0F);

                            world.spawnParticles(
                                    new BlockStateParticleEffect(ParticleTypes.BLOCK, blockToFillWith),
                                    checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5,
                                    10, 0.5, 0.5, 0.5, 0.1);
                        }
                    } else {
                        blockFrequency.put(currentState, blockFrequency.getOrDefault(currentState, 0) + 1);
                    }
                }
            }
        }

        // Store the operation in the undo history if there are changes and a player UUID
        if (!operationChanges.isEmpty() && playerUUID != null) {
            playerUndoHistory.computeIfAbsent(playerUUID, k -> new LinkedList<>()).addFirst(operationChanges);

            // Trim history if it exceeds the maximum size
            while (playerUndoHistory.get(playerUUID).size() > MAX_UNDO_HISTORY) {
                playerUndoHistory.get(playerUUID).removeLast();
            }

            // Clear redo history when a new operation is performed
            playerRedoHistory.computeIfAbsent(playerUUID, k -> new LinkedList<>()).clear();
        }
    }

    private BlockState findBestFillingBlock(ServerWorld world, BlockPos holePos, HashMap<BlockState, Integer> blockFrequency) {
        BlockPos[] offsets = {
                holePos.north(), holePos.south(), holePos.east(), holePos.west(),
                holePos.north().east(), holePos.north().west(), holePos.south().east(), holePos.south().west(),
                holePos.up(), holePos.down()
        };

        Map<BlockState, Integer> localFrequency = new HashMap<>();

        // Check blocks in immediate vicinity with weight of 2
        for (BlockPos offset : offsets) {
            BlockState adjacentBlock = world.getBlockState(offset);
            if (!adjacentBlock.isAir() && adjacentBlock.getBlock() != Blocks.WATER) {
                localFrequency.merge(adjacentBlock, 2, Integer::sum);
            }
        }

        // Add global frequency with weight of 1
        blockFrequency.forEach((state, count) ->
                localFrequency.merge(state, count, Integer::sum));

        return localFrequency.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}