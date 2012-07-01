package org.bukkit.craftbukkit.inventory;

import java.util.Map;
import net.minecraft.server.NBTTagCompound;
import net.minecraft.server.NBTTagList;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.configuration.serialization.DelegateDeserialization;

@DelegateDeserialization(ItemStack.class)
public class CraftItemStack extends ItemStack {
    private net.minecraft.server.ItemStack item;
    private boolean processedEnchantments = true;

    public CraftItemStack(net.minecraft.server.ItemStack item) {
        super(
            item != null ? item.id: 0,
            item != null ? item.count : 0,
            (short) (item != null ? item.getData() : 0)
        );
        this.item = item;
        this.getNMSEnchantments();
    }

    public CraftItemStack(ItemStack item) {
        this(item.getTypeId(), item.getAmount(), item.getDurability());
        addUnsafeEnchantments(item.getEnchantments());
    }

    /* 'Overwritten' constructors from ItemStack, yay for Java sucking */
    public CraftItemStack(final int type) {
        this(type, 1);
    }

    public CraftItemStack(final Material type) {
        this(type, 1);
    }

    public CraftItemStack(final int type, final int amount) {
        this(type, amount, (byte) 0);
    }

    public CraftItemStack(final Material type, final int amount) {
        this(type.getId(), amount);
    }

    public CraftItemStack(final int type, final int amount, final short damage) {
        this(type, amount, damage, null);
    }

    public CraftItemStack(final Material type, final int amount, final short damage) {
        this(type.getId(), amount, damage);
    }

    public CraftItemStack(final Material type, final int amount, final short damage, final Byte data) {
        this(type.getId(), amount, damage, data);
    }

    public CraftItemStack(int type, int amount, short damage, Byte data) {
        this(new net.minecraft.server.ItemStack(type, amount, data != null ? data : damage));
    }

    @Override
    public void setTypeId(int type) {
        if (type <= 0) {
            item = null;
        } else {
            if (item == null) {
                item = new net.minecraft.server.ItemStack(type, 1, 0);
                super.setAmount(1);
                super.setDurability((short) 0);
            } else {
                item.id = type;
            }
        }
        super.setTypeId(type);
    }

    @Override
    public void setAmount(int amount) {
        if (item != null) {
            item.count = amount;
        }
        super.setAmount(amount);
    }

    @Override
    public void setDurability(final short durability) {
        if (item != null) {
            item.setData(durability);
        }
        super.setDurability(durability);
    }

    @Override
    public int getMaxStackSize() {
        return item == null ? 0 : item.getItem().getMaxStackSize();
    }

    @Override
    public void addUnsafeEnchantment(Enchantment ench, int level) {
        super.addUnsafeEnchantment(ench, level);
        this.processedEnchantments = false;
    }

    @Override
    public int removeEnchantment(Enchantment ench) {
        this.processedEnchantments = false;
        return super.removeEnchantment(ench);
    }

    private void getNMSEnchantments() {
        NBTTagList list = (item == null) ? null : item.getEnchantments();

        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                short id = ((NBTTagCompound) list.get(i)).getShort("id");
                short level = ((NBTTagCompound) list.get(i)).getShort("lvl");

                enchantments.put(Enchantment.getById(id), (int) level);
            }
        }
    }

    private void rebuildEnchantments() {
        if (item == null) return;

        NBTTagCompound tag = item.tag;
        NBTTagList list = new NBTTagList("ench");

        if (tag == null) {
            tag = item.tag = new NBTTagCompound();
        }

        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            NBTTagCompound subtag = new NBTTagCompound();

            subtag.setShort("id", (short) entry.getKey().getId());
            subtag.setShort("lvl", (short) (int) entry.getValue());

            list.add(subtag);
        }

        if (enchantments.isEmpty()) {
            tag.remove("ench");
        } else {
            tag.set("ench", list);
        }

        processedEnchantments = true;
    }

    public net.minecraft.server.ItemStack getHandle() {
        if (!this.processedEnchantments) {
            this.rebuildEnchantments();
        }
        return item;
    }

    @Override
    public CraftItemStack clone() {
        CraftItemStack itemStack = (CraftItemStack) super.clone();
        if (this.item != null) {
            itemStack.item = this.item.cloneItemStack();
        }
        return itemStack;
    }

    public static net.minecraft.server.ItemStack createNMSItemStack(ItemStack original) {
        if (original == null || original.getTypeId() <= 0) {
            return null;
        } else if (original instanceof CraftItemStack) {
            return ((CraftItemStack) original).getHandle();
        } else {
            return new CraftItemStack(original).getHandle();
        }
    }
}
