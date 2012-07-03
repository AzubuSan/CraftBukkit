package org.bukkit.craftbukkit.inventory.meta;

import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class CraftItemFactory implements ItemFactory {
    private static CraftItemFactory instance = new CraftItemFactory();

    // TODO: Add Enchantments
    private CraftItemFactory() {}

    public boolean isValidMeta(ItemMeta meta, ItemStack itemstack) {
        if (itemstack == null || meta == null) {
            return false;
        }

        Material material = itemstack.getType();
        if (material == null) {
            material = Material.AIR;
        }

        switch (material) {
            case WRITTEN_BOOK:
            case BOOK_AND_QUILL:
                return meta instanceof CraftBookMeta;
        }

        return meta instanceof CraftEmptyMeta;
    }

    private CraftItemMeta getItemMeta(Material material, ItemStack itemstack) {
        if (material == null) {
            material = itemstack.getType();

            if (material == null) {
                material = Material.AIR;
            }
        }

        switch (material) {
            case WRITTEN_BOOK:
            case BOOK_AND_QUILL:
                if (itemstack instanceof CraftItemStack) {
                    return new CraftBookMeta((CraftItemStack) itemstack);
                } else {
                    return new CraftBookMeta();
                }
            default:
                return new CraftEmptyMeta();
        }
    }

    public ItemMeta getItemMeta(ItemStack itemstack) {
        return getItemMeta(null, itemstack);
    }

    public ItemMeta getItemMeta(Material material) {
        return getItemMeta(material, null);
    }

    public boolean equals(ItemMeta meta1, ItemMeta meta2) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public static CraftItemFactory getFactory() {
        return instance;
    }
}
