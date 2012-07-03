package org.bukkit.craftbukkit.inventory.meta;

import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public abstract class CraftItemMeta implements ItemMeta {
    CraftItemMeta() {}

    public CraftItemMeta clone() {
        CraftItemMeta clonedStack = null;

        try {
            clonedStack = (CraftItemMeta) super.clone();
        } catch (CloneNotSupportedException ex) {}

        return clonedStack;
    }

    public abstract void applyToItem(CraftItemStack item);
}
