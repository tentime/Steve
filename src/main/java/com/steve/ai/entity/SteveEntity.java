package com.steve.ai.entity;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.memory.SteveMemory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SteveEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> STEVE_NAME = 
        SynchedEntityData.defineId(SteveEntity.class, EntityDataSerializers.STRING);

    private String steveName;
    private SteveMemory memory;
    private ActionExecutor actionExecutor;
    private int tickCounter = 0;
    private boolean isFlying = false;
    private boolean isInvulnerable = false;

    // Inventory — 27 slots (same as a single chest)
    private final SimpleContainer inventory = new SimpleContainer(27);
    private static final double PICKUP_RANGE = 3.0;

    public SteveEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.steveName = "Steve";
        this.memory = new SteveMemory(this);
        this.actionExecutor = new ActionExecutor(this);
        this.setCustomNameVisible(true);
        
        this.isInvulnerable = true;
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.ATTACK_DAMAGE, 8.0D)
            .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STEVE_NAME, "Steve");
    }

    @Override
    public void tick() {
        super.tick();
        
        if (!this.level().isClientSide) {
            actionExecutor.tick();
            pickupNearbyItems();
        }
    }

    /**
     * Pick up item entities within PICKUP_RANGE.
     * Items get added to our SimpleContainer inventory.
     */
    private void pickupNearbyItems() {
        // Only check every 5 ticks (4 times per second) to save perf
        tickCounter++;
        if (tickCounter % 5 != 0) return;

        AABB pickupBox = this.getBoundingBox().inflate(PICKUP_RANGE);
        List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class, pickupBox);

        for (ItemEntity itemEntity : items) {
            if (itemEntity.isRemoved()) continue;
            // Items have a short pickup delay after spawning
            if (itemEntity.hasPickUpDelay()) continue;

            ItemStack stack = itemEntity.getItem();
            ItemStack remaining = addToInventory(stack.copy());

            if (remaining.isEmpty()) {
                // Fully picked up
                itemEntity.discard();
            } else if (remaining.getCount() < stack.getCount()) {
                // Partially picked up (inventory almost full)
                itemEntity.setItem(remaining);
            }
            // else: inventory full, leave the item
        }
    }

    /**
     * Try to add an ItemStack to the inventory. Returns whatever could NOT fit.
     */
    public ItemStack addToInventory(ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()) {
                inventory.setItem(i, stack);
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameTags(slot, stack)) {
                int canFit = slot.getMaxStackSize() - slot.getCount();
                if (canFit > 0) {
                    int transfer = Math.min(canFit, stack.getCount());
                    slot.grow(transfer);
                    stack.shrink(transfer);
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                }
            }
        }
        return stack; // whatever didn't fit
    }

    /**
     * Returns the Steve's item inventory (27 slots).
     */
    public SimpleContainer getInventoryContainer() {
        return inventory;
    }

    /**
     * Returns true if the inventory has at least one non-empty slot.
     */
    public boolean hasItemsInInventory() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Returns a human-readable summary of inventory contents (for LLM context).
     */
    public String getInventorySummary() {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                if (count > 0) sb.append(", ");
                sb.append(stack.getCount()).append("x ").append(stack.getItem().toString());
                count++;
            }
        }
        return count == 0 ? "[empty]" : sb.toString();
    }

    public void setSteveName(String name) {
        this.steveName = name;
        this.entityData.set(STEVE_NAME, name);
        this.setCustomName(Component.literal(name));
    }

    public String getSteveName() {
        return this.steveName;
    }

    public SteveMemory getMemory() {
        return this.memory;
    }

    public ActionExecutor getActionExecutor() {
        return this.actionExecutor;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("SteveName", this.steveName);
        
        CompoundTag memoryTag = new CompoundTag();
        this.memory.saveToNBT(memoryTag);
        tag.put("Memory", memoryTag);

        // Save inventory
        net.minecraft.nbt.ListTag invList = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                stack.save(slotTag);
                invList.add(slotTag);
            }
        }
        tag.put("Inventory", invList);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("SteveName")) {
            this.setSteveName(tag.getString("SteveName"));
        }
        
        if (tag.contains("Memory")) {
            this.memory.loadFromNBT(tag.getCompound("Memory"));
        }

        // Load inventory
        if (tag.contains("Inventory")) {
            inventory.clearContent();
            net.minecraft.nbt.ListTag invList = tag.getList("Inventory", 10); // 10 = Compound type
            for (int i = 0; i < invList.size(); i++) {
                CompoundTag slotTag = invList.getCompound(i);
                int slot = slotTag.getByte("Slot") & 0xFF;
                if (slot < inventory.getContainerSize()) {
                    inventory.setItem(slot, ItemStack.of(slotTag));
                }
            }
        }
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                       MobSpawnType spawnType, @Nullable SpawnGroupData spawnData,
                                       @Nullable CompoundTag tag) {
        spawnData = super.finalizeSpawn(level, difficulty, spawnType, spawnData, tag);
        return spawnData;
    }

    public void sendChatMessage(String message) {
        if (this.level().isClientSide) return;
        
        Component chatComponent = Component.literal("<" + this.steveName + "> " + message);
        this.level().players().forEach(player -> player.sendSystemMessage(chatComponent));
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.world.damagesource.DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);
    }

    public void setFlying(boolean flying) {
        this.isFlying = flying;
        this.setNoGravity(flying);
        this.setInvulnerableBuilding(flying);
    }

    public boolean isFlying() {
        return this.isFlying;
    }

    /**
     * Set invulnerability for building (immune to ALL damage: fire, lava, suffocation, fall, etc.)
     */
    public void setInvulnerableBuilding(boolean invulnerable) {
        this.isInvulnerable = invulnerable;
        this.setInvulnerable(invulnerable); // Minecraft's built-in invulnerability
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(net.minecraft.world.damagesource.DamageSource source) {
        return true;
    }

    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (this.isFlying && !this.level().isClientSide) {
            double motionY = this.getDeltaMovement().y;
            
            if (this.getNavigation().isInProgress()) {
                super.travel(travelVector);
                
                // But add ability to move vertically freely
                if (Math.abs(motionY) < 0.1) {
                    // Small upward force to prevent falling
                    this.setDeltaMovement(this.getDeltaMovement().add(0, 0.05, 0));
                }
            } else {
                super.travel(travelVector);
            }
        } else {
            super.travel(travelVector);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier, net.minecraft.world.damagesource.DamageSource source) {
        // No fall damage when flying
        if (this.isFlying) {
            return false;
        }
        return super.causeFallDamage(distance, damageMultiplier, source);
    }
}
