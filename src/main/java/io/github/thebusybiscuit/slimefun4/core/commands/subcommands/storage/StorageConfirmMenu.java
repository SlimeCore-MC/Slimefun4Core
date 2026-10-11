package io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.items.CustomItemStack;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;

import java.util.List;

/**
 * A reusable confirmation screen for the destructive actions in {@code /sf storage gui}.
 *
 * <p>The confirm button re-checks {@link StorageMenuLayout#PERM_MANAGE} at click time, so a
 * permission revoked while this screen sits open still takes effect. Chain two of these to
 * require a double confirmation.
 *
 * @author SlimefunCore Contributors
 */
@Beta
final class StorageConfirmMenu {

    private StorageConfirmMenu() {}

    static void open(
            @Nonnull Player player,
            @Nonnull String title,
            @Nonnull Material icon,
            @Nonnull String heading,
            @Nonnull List<String> details,
            @Nonnull ItemStack confirmItem,
            @Nonnull Runnable onConfirm,
            @Nonnull Runnable onCancel) {

        ChestMenu menu = StorageMenuLayout.newMenu(title);

        StorageMenuLayout.fillBackground(menu);

        StorageMenuLayout.addInfoItem(menu, 13, icon, heading, details);

        StorageMenuLayout.addManagedButton(menu, 29, confirmItem,
            (pl, right, shift) -> {
                pl.closeInventory();
                onConfirm.run();
            });

        StorageMenuLayout.addButton(menu, 33,
            CustomItemStack.create(Material.RED_WOOL, "&c> Cancel",
                StorageMenuLayout.lore("&7Nothing is changed.", "", "&eClick &7to go back")),
            (pl, right, shift) -> onCancel.run());

        menu.open(player);
    }
}
