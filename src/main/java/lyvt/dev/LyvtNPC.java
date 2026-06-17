package lyvt.dev;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

// If you read this, Heyyy c: how are you?

public final class LyvtNPC extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private NamespacedKey commandKey;
    private File npcFile;
    private FileConfiguration npcConfig;
    private final HashMap<UUID, Long> cooldowns = new HashMap<>();

    private final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "LyvtNPC" + ChatColor.DARK_GRAY + "] " + ChatColor.GRAY;

    @Override
    public void onEnable() {
        this.commandKey = new NamespacedKey(this, "click_command");

        createNPCConfig();

        // Register main command
        getCommand("npc").setExecutor(this);
        getCommand("npc").setTabCompleter(this);

        getServer().getPluginManager().registerEvents(this, this);

        clearExistingNPCs();
        loadNPCsFromConfig();

        // Rotation Timer
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                    if (display.isValid() && display.getPersistentDataContainer().has(commandKey, PersistentDataType.STRING)) {
                        Location loc = display.getLocation();
                        loc.setYaw((loc.getYaw() + 2.0f) % 360.0f);
                        display.teleport(loc);
                    }
                }
            }
        }, 0L, 1L);
    }

    @Override
    public void onDisable() {
        clearExistingNPCs();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("npc")) {
            return false;
        }

        // Show help if no arguments are provided
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        // SUBCOMMAND: reload
        if (subCommand.equals("reload")) {
            if (!sender.hasPermission("lyvtnpc.reload")) {
                sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to do this!");
                return true;
            }
            npcConfig = YamlConfiguration.loadConfiguration(npcFile);
            clearExistingNPCs();
            loadNPCsFromConfig();
            sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration successfully reloaded!");
            return true;
        }

        // Console block for player-only subcommands
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Only players can use this command.");
            return true;
        }

        // SUBCOMMAND: delete
        if (subCommand.equals("delete")) {
            if (!player.hasPermission("lyvtnpc.delete")) {
                player.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to do this!");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(PREFIX + ChatColor.RED + "Usage: /npc delete <Name/ID>");
                return true;
            }

            String id = args[1].toLowerCase();
            if (!npcConfig.contains("npcs." + id)) {
                player.sendMessage(PREFIX + ChatColor.RED + "This NPC does not exist!");
                return true;
            }

            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity.getScoreboardTags().contains("lyvtnpc_" + id)) {
                        entity.remove();
                    }
                }
            }

            npcConfig.set("npcs." + id, null);
            saveConfigQuietly();

            player.sendMessage(PREFIX + ChatColor.GREEN + "NPC '" + ChatColor.YELLOW + id + ChatColor.GREEN + "' was successfully deleted!");
            return true;
        }

        // SUBCOMMAND: spawn
        if (subCommand.equals("spawn")) {
            if (!player.hasPermission("lyvtnpc.spawn")) {
                player.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to do this!");
                return true;
            }

            if (args.length < 5) {
                player.sendMessage(PREFIX + ChatColor.RED + "Usage: /npc spawn <Name> <Size> <Item> <Command>");
                return true;
            }

            String id = args[1].toLowerCase();
            if (npcConfig.contains("npcs." + id)) {
                player.sendMessage(PREFIX + ChatColor.RED + "An NPC with this name already exists!");
                return true;
            }

            float scale;
            try {
                scale = Float.parseFloat(args[2]);
                if (scale <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage(PREFIX + ChatColor.RED + "The size must be a number greater than 0 (e.g., 1.0 or 1.5)!");
                return true;
            }

            Material material = Material.matchMaterial(args[3].toUpperCase());
            if (material == null || !material.isItem()) {
                player.sendMessage(PREFIX + ChatColor.RED + "Invalid item material!");
                return true;
            }

            String executeCommand = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
            Location loc = player.getLocation();

            spawnNPC(id, loc, material, scale, executeCommand);
            saveNPCToConfig(id, loc, material, scale, executeCommand);

            player.sendMessage(PREFIX + ChatColor.GREEN + "Rotating NPC '" + ChatColor.YELLOW + id + ChatColor.GREEN + "' has been created!");
            return true;
        }

        // Unknown subcommand
        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + ChatColor.GOLD + "--- LyvtNPC Help ---");
        if (sender.hasPermission("lyvtnpc.spawn")) {
            sender.sendMessage(ChatColor.YELLOW + "/npc spawn <Name> <Size> <Item> <Command>" + ChatColor.GRAY + " - Spawns an NPC");
        }
        if (sender.hasPermission("lyvtnpc.delete")) {
            sender.sendMessage(ChatColor.YELLOW + "/npc delete <Name/ID>" + ChatColor.GRAY + " - Deletes an NPC");
        }
        if (sender.hasPermission("lyvtnpc.reload")) {
            sender.sendMessage(ChatColor.YELLOW + "/npc reload" + ChatColor.GRAY + " - Reloads the config");
        }
    }

    private void spawnNPC(String id, Location loc, Material material, float scale, String command) {
        Location displayLoc = loc.clone().add(0, 0.5, 0);
        ItemDisplay itemDisplay = (ItemDisplay) displayLoc.getWorld().spawnEntity(displayLoc, EntityType.ITEM_DISPLAY);
        itemDisplay.setItemStack(new ItemStack(material));
        itemDisplay.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
        itemDisplay.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);

        Transformation transformation = itemDisplay.getTransformation();
        transformation.getScale().set(scale, scale, scale);
        itemDisplay.setTransformation(transformation);

        itemDisplay.getPersistentDataContainer().set(commandKey, PersistentDataType.STRING, "visual");
        itemDisplay.addScoreboardTag("lyvtnpc_" + id);

        Location interactionLoc = loc.clone().add(0, -1.0, 0);
        Interaction interaction = (Interaction) interactionLoc.getWorld().spawnEntity(interactionLoc, EntityType.INTERACTION);
        interaction.setInteractionWidth(3.0f);
        interaction.setInteractionHeight(3.0f);
        interaction.getPersistentDataContainer().set(commandKey, PersistentDataType.STRING, command);
        interaction.addScoreboardTag("lyvtnpc_" + id);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Interaction interaction) {
            if (interaction.getPersistentDataContainer().has(commandKey, PersistentDataType.STRING)) {
                Player player = event.getPlayer();

                if (cooldowns.containsKey(player.getUniqueId())) {
                    long secondsLeft = (cooldowns.get(player.getUniqueId()) + 3000) - System.currentTimeMillis();
                    if (secondsLeft > 0) {
                        int remaining = (int) Math.ceil(secondsLeft / 1000.0);
                        player.sendMessage(PREFIX + ChatColor.RED + "Please wait " + remaining + " more seconds!");
                        return;
                    }
                }

                String cmd = interaction.getPersistentDataContainer().get(commandKey, PersistentDataType.STRING);
                if (cmd != null && !cmd.equals("visual")) {
                    player.performCommand(cmd);
                    cooldowns.put(player.getUniqueId(), System.currentTimeMillis());
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!command.getName().equalsIgnoreCase("npc")) return completions;

        if (args.length == 1) {
            if (sender.hasPermission("lyvtnpc.spawn")) completions.add("spawn");
            if (sender.hasPermission("lyvtnpc.delete")) completions.add("delete");
            if (sender.hasPermission("lyvtnpc.reload")) completions.add("reload");
            return completions.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }

        String subCommand = args[0].toLowerCase();

        // Completion for /npc delete
        if (subCommand.equals("delete") && sender.hasPermission("lyvtnpc.delete")) {
            if (args.length == 2 && npcConfig.contains("npcs")) {
                List<String> npcs = new ArrayList<>(npcConfig.getConfigurationSection("npcs").getKeys(false));
                return npcs.stream().filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
        }

        // Completion for /npc spawn
        if (subCommand.equals("spawn") && sender.hasPermission("lyvtnpc.spawn")) {
            if (args.length == 2) {
                completions.add("<Name>");
            } else if (args.length == 3) {
                completions.addAll(Arrays.asList("1.0", "1.5", "2.0", "3.0"));
                return completions.stream().filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).collect(Collectors.toList());
            } else if (args.length == 4) {
                String search = args[3].toLowerCase();
                for (Material mat : Material.values()) {
                    if (mat.isItem() && mat.name().toLowerCase().startsWith(search)) {
                        completions.add(mat.name());
                    }
                }
                return completions;
            } else if (args.length == 5) {
                completions.add("<Command_without_slash>");
            }
            return completions;
        }

        return completions;
    }

    private void createNPCConfig() {
        npcFile = new File(getDataFolder(), "npcs.yml");
        if (!npcFile.exists()) {
            npcFile.getParentFile().mkdirs();
            try {
                npcFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        npcConfig = YamlConfiguration.loadConfiguration(npcFile);
    }

    private void saveNPCToConfig(String id, Location loc, Material material, float scale, String command) {
        String path = "npcs." + id + ".";
        npcConfig.set(path + "item", material.name());
        npcConfig.set(path + "size", scale);
        npcConfig.set(path + "command", command);
        npcConfig.set(path + "position.world", loc.getWorld().getName());
        npcConfig.set(path + "position.x", loc.getX());
        npcConfig.set(path + "position.y", loc.getY());
        npcConfig.set(path + "position.z", loc.getZ());
        saveConfigQuietly();
    }

    private void saveConfigQuietly() {
        try {
            npcConfig.save(npcFile);
        } catch (IOException e) {
            getLogger().severe("Could not save npcs.yml!");
            e.printStackTrace();
        }
    }

    private void loadNPCsFromConfig() {
        if (!npcConfig.contains("npcs")) return;

        for (String id : npcConfig.getConfigurationSection("npcs").getKeys(false)) {
            String path = "npcs." + id + ".";

            String materialStr = npcConfig.getString(path + "item");
            float scale = (float) npcConfig.getDouble(path + "size");
            String command = npcConfig.getString(path + "command");

            String worldName = npcConfig.getString(path + "position.world");
            double x = npcConfig.getDouble(path + "position.x");
            double y = npcConfig.getDouble(path + "position.y");
            double z = npcConfig.getDouble(path + "position.z");

            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                Location loc = new Location(world, x, y, z);
                Material material = Material.getMaterial(materialStr);
                if (material != null) {
                    spawnNPC(id, loc, material, scale, command);
                }
            }
        }
    }

    private void clearExistingNPCs() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                for (String tag : entity.getScoreboardTags()) {
                    if (tag.startsWith("lyvtnpc_")) {
                        entity.remove();
                        break;
                    }
                }
            }
        }
    }
}