
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class SnowballHoleFiller extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onSnowballHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Snowball) {
            Location loc = event.getEntity().getLocation();
            World world = loc.getWorld();

            // Fill the hole below the point where the snowball landed
            for (int y = loc.getBlockY(); y >= 0; y--) {
                Block block = world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
                if (block.getType() == Material.AIR) {
                    block.setType(Material.DIRT);
                } else {
                    break;
                }
            }
        }
    }
}
