package io.github.thebusybiscuit.slimefun4.implementation.items.weapons;

import java.util.Collection;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemSetting;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.settings.IntRangeSetting;
import io.github.thebusybiscuit.slimefun4.core.handlers.BowShootHandler;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.utils.compatibility.VersionedParticle;

/**
 * The {@link ExplosiveBow} is a {@link SlimefunBow} which creates a fake explosion when it hits
 * a {@link LivingEntity}. Any nearby {@link LivingEntity LivingEntities} get pushed away and
 * take a little bit of damage, similar to an "Area of Effect" damage.
 * 
 * @author TheBusyBiscuit
 * @author Linox
 * 
 * @see SlimefunBow
 *
 */
public class ExplosiveBow extends SlimefunBow {

    private final ItemSetting<Integer> range = new IntRangeSetting(this, "explosion-range", 1, 3, Integer.MAX_VALUE);

    @ParametersAreNonnullByDefault
    public ExplosiveBow(ItemGroup itemGroup, SlimefunItemStack item, ItemStack[] recipe) {
        super(itemGroup, item, recipe);

        addItemSetting(range);
    }

    @Nonnull
    @Override
    @SuppressWarnings("removal")
    public BowShootHandler onShoot() {
        return (e, target) -> {
            target.getWorld().spawnParticle(VersionedParticle.EXPLOSION, target.getLocation(), 1);
            SoundEffect.EXPLOSIVE_BOW_HIT_SOUND.playAt(target.getLocation(), SoundCategory.PLAYERS);
            int radius = range.getValue();

            Collection<Entity> entities = target.getWorld().getNearbyEntities(target.getLocation(), radius, radius, radius, this::canDamage);
            DamageSource damageSource = createExplosionSource(e.getDamager());

            for (Entity nearby : entities) {
                LivingEntity entity = (LivingEntity) nearby;

                Vector distanceVector = entity.getLocation().toVector().subtract(target.getLocation().toVector()).add(new Vector(0, 0.75, 0));

                double distanceSquared = distanceVector.lengthSquared();
                double damage = e.getDamage() * (1 - (distanceSquared / (2 * range.getValue() * range.getValue())));

                if (!entity.getUniqueId().equals(target.getUniqueId())) {
                    EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(e.getDamager(), entity, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, damageSource, damage);
                    Bukkit.getPluginManager().callEvent(event);

                    if (!event.isCancelled()) {
                        distanceVector.setY(0.75);
                        Vector knockback = distanceVector.normalize().multiply(2);
                        entity.setVelocity(entity.getVelocity().add(knockback));
                        entity.damage(event.getDamage());
                    }
                }
            }
        };
    }

    /**
     * Builds the {@link DamageSource} of the fake explosion.
     *
     * <p>The damager handed to a bow handler is the arrow itself. A {@link DamageSource} that
     * names a causing entity has to name a direct entity as well, so the arrow becomes the
     * direct entity and its shooter, when there is one, becomes the cause.
     *
     * @param damager The entity that dealt the arrow hit
     * @return A damage source that is accepted by the server implementation
     */
    @Nonnull
    private DamageSource createExplosionSource(@Nonnull Entity damager) {
        Entity causingEntity = damager;

        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            causingEntity = shooter;
        }

        return DamageSource.builder(DamageType.EXPLOSION)
            .withDirectEntity(damager)
            .withCausingEntity(causingEntity)
            .build();
    }

    private boolean canDamage(@Nonnull Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof ArmorStand) && entity.isValid();
    }
}
