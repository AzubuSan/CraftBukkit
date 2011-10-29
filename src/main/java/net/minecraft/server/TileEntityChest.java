package net.minecraft.server;

public class TileEntityChest extends TileEntity implements IInventory {

    private ItemStack[] items = new ItemStack[27]; // CraftBukkit
    public boolean a = false;
    public TileEntityChest b;
    public TileEntityChest c;
    public TileEntityChest d;
    public TileEntityChest e;
    public float f;
    public float g;
    public int h;
    private int j;

    // CraftBukkit start
    public ItemStack[] getContents() {
        return this.items;
    }
    // CraftBukkit end

    public TileEntityChest() {}

    public int getSize() {
        return 27;
    }

    public ItemStack getItem(int i) {
        return this.items[i];
    }

    public ItemStack splitStack(int i, int j) {
        if (this.items[i] != null) {
            ItemStack itemstack;

            if (this.items[i].count <= j) {
                itemstack = this.items[i];
                this.items[i] = null;
                this.update();
                return itemstack;
            } else {
                itemstack = this.items[i].a(j);
                if (this.items[i].count == 0) {
                    this.items[i] = null;
                }

                this.update();
                return itemstack;
            }
        } else {
            return null;
        }
    }

    public void setItem(int i, ItemStack itemstack) {
        this.items[i] = itemstack;
        if (itemstack != null && itemstack.count > this.getMaxStackSize()) {
            itemstack.count = this.getMaxStackSize();
        }

        this.update();
    }

    public String getName() {
        return "Chest";
    }

    public void a(NBTTagCompound nbttagcompound) {
        super.a(nbttagcompound);
        NBTTagList nbttaglist = nbttagcompound.m("Items");

        this.items = new ItemStack[this.getSize()];

        for (int i = 0; i < nbttaglist.d(); ++i) {
            NBTTagCompound nbttagcompound1 = (NBTTagCompound) nbttaglist.a(i);
            int j = nbttagcompound1.d("Slot") & 255;

            if (j >= 0 && j < this.items.length) {
                this.items[j] = ItemStack.a(nbttagcompound1);
            }
        }
    }

    public void b(NBTTagCompound nbttagcompound) {
        super.b(nbttagcompound);
        NBTTagList nbttaglist = new NBTTagList();

        for (int i = 0; i < this.items.length; ++i) {
            if (this.items[i] != null) {
                NBTTagCompound nbttagcompound1 = new NBTTagCompound();

                nbttagcompound1.a("Slot", (byte) i);
                this.items[i].b(nbttagcompound1);
                nbttaglist.a((NBTBase) nbttagcompound1);
            }
        }

        nbttagcompound.a("Items", (NBTBase) nbttaglist);
    }

    public int getMaxStackSize() {
        return 64;
    }

    public boolean a(EntityHuman entityhuman) {
        if (this.world == null) return true; // CraftBukkit
        return this.world.getTileEntity(this.x, this.y, this.z) != this ? false : entityhuman.e((double) this.x + 0.5D, (double) this.y + 0.5D, (double) this.z + 0.5D) <= 64.0D;
    }

    public void d() {
        super.d();
        this.a = false;
    }

    public void h() {
        if (!this.a) {
            this.a = true;
            this.b = null;
            this.c = null;
            this.d = null;
            this.e = null;
            if (this.world.getTypeId(this.x - 1, this.y, this.z) == Block.CHEST.id) {
                this.d = (TileEntityChest) this.world.getTileEntity(this.x - 1, this.y, this.z);
            }

            if (this.world.getTypeId(this.x + 1, this.y, this.z) == Block.CHEST.id) {
                this.c = (TileEntityChest) this.world.getTileEntity(this.x + 1, this.y, this.z);
            }

            if (this.world.getTypeId(this.x, this.y, this.z - 1) == Block.CHEST.id) {
                this.b = (TileEntityChest) this.world.getTileEntity(this.x, this.y, this.z - 1);
            }

            if (this.world.getTypeId(this.x, this.y, this.z + 1) == Block.CHEST.id) {
                this.e = (TileEntityChest) this.world.getTileEntity(this.x, this.y, this.z + 1);
            }

            if (this.b != null) {
                this.b.d();
            }

            if (this.e != null) {
                this.e.d();
            }

            if (this.c != null) {
                this.c.d();
            }

            if (this.d != null) {
                this.d.d();
            }
        }
    }

    // CraftBukkit start
    private TileEntityChest getTileEntity(int x, int y, int z) {
        if (this.world == null) return null; // CraftBukkit
        TileEntity entity = this.world.getTileEntity(x, y, z);

        if (entity instanceof TileEntityChest) {
            return (TileEntityChest)entity;
        } else {
            String name = "null";
            if (entity != null) {
                name = entity.toString();
            }
            world.getServer().getLogger().severe("Block at " + x + "," + y + "," + z + " is a chest but has a " + name);
            return null;
        }
    }
    // CraftBukkit end

    public void k_() {
        super.k_();
        if (this.world == null) return; // CraftBukkit
        this.h();
        if (++this.j % (20 * 4) == 0) { // CraftBukkit
            this.world.playNote(this.x, this.y, this.z, 1, this.h);
        }

        this.g = this.f;
        float f = 0.1F;
        double d0;
        double d1;

        if (this.h > 0 && this.f == 0.0F && this.b == null && this.d == null) {
            d0 = (double) this.x + 0.5D;
            d1 = (double) this.z + 0.5D;
            if (this.e != null) {
                d1 += 0.5D;
            }

            if (this.c != null) {
                d0 += 0.5D;
            }

            this.world.makeSound(d0, (double) this.y + 0.5D, d1, "random.chest_open", 1.0F, this.world.random.nextFloat() * 0.1F + 0.9F);
        }

        if (this.h == 0 && this.f > 0.0F || this.h > 0 && this.f < 1.0F) {
            if (this.h > 0) {
                this.f += f;
            } else {
                this.f -= f;
            }

            if (this.f > 1.0F) {
                this.f = 1.0F;
            }

            if (this.f < 0.0F) {
                this.f = 0.0F;
                if (this.b == null && this.d == null) {
                    d0 = (double) this.x + 0.5D;
                    d1 = (double) this.z + 0.5D;
                    if (this.e != null) {
                        d1 += 0.5D;
                    }

                    if (this.c != null) {
                        d0 += 0.5D;
                    }

                    this.world.makeSound(d0, (double) this.y + 0.5D, d1, "random.chest_close", 1.0F, this.world.random.nextFloat() * 0.1F + 0.9F);
                }
            }
        }
    }

    public void b(int i, int j) {
        if (i == 1) {
            this.h = j;
        }
    }

    public void z_() {
        ++this.h;
        if (this.world == null) return; // CraftBukkit
        this.world.playNote(this.x, this.y, this.z, 1, this.h);
    }

    public void g() {
        --this.h;
        if (this.world == null) return; // CraftBukkit
        this.world.playNote(this.x, this.y, this.z, 1, this.h);
    }

    public void i() {
        this.d();
        this.h();
        super.i();
    }
}
