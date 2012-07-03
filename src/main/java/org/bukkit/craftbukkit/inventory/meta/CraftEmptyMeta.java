package org.bukkit.craftbukkit.inventory.meta;

import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.meta.EmptyMeta;

public final class CraftEmptyMeta extends CraftItemMeta implements EmptyMeta {
    public void applyToItem(CraftItemStack item) {}
}