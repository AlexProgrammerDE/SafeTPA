package not.hub.safetp;

import io.papermc.lib.PaperLib;
import not.hub.safetp.tasks.ClearOldRequestsRunnable;
import not.hub.safetp.tasks.UnvanishRunnable;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public final class SafeTP extends JavaPlugin {

    private final RequestManager requestManager = new RequestManager();

    private boolean configMultiRequest;
    private int configRequestTimeoutSeconds;
    private int configUnvanishDelay;
    private boolean configSpawnTpDeny;
    private int configSpawnTpDenyRadius;

    static void sendMessage(Player player, String message) {
        player.sendMessage(message);
    }

    @Override
    public void onEnable() {

        PaperLib.suggestPaper(this);

        loadConfig();

        new ClearOldRequestsRunnable(this).runTaskTimer(this, 0, 20);

    }

    private int sanitizeConfigIntValue(String path) {

        int value = getConfig().getInt(path);
        if (value <= 0) {
            value = (int) getConfig().getDefaults().get(path);
            getConfig().set(path, value);
            saveConfig();
        }
        return value;

    }

    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String commandLabel, String[] args) {

        Player sender = null;

        if (commandSender instanceof Player) {
            sender = (Player) commandSender;
        }

        if (sender == null) {
            return false;
        }

        // 0 arg commands

        if (command.getLabel().equalsIgnoreCase("tpt")) {
            toggleRequestBlock(sender);
            return true;
        }

        // > 0 arg commands

        if (args.length == 0) {
            sendMessage(sender, ChatColor.GOLD + "You need to run this command with an argument, like this:");
            sendMessage(sender, "/tpa NAME " + ChatColor.GOLD + ".. or .. " + ChatColor.RESET + "/tpy NAME " + ChatColor.GOLD + ".. or .. " + ChatColor.RESET + "/tpn NAME");
            return false;
        }

        if (isInvalidTarget(args[0])) {
            sendMessage(sender, ChatColor.GOLD + "Player not found.");
            return false;
        }

        if (sender.getName().equalsIgnoreCase(args[0])) {
            sendMessage(sender, ChatColor.GOLD + "You cant run this command on yourself!");
            return false;
        }

        if (command.getLabel().equalsIgnoreCase("tpa")) {
            askTP(getServer().getPlayer(args[0]), sender);
            return true;
        }

        if (command.getLabel().equalsIgnoreCase("tpy")) {
            acceptTP(sender, getServer().getPlayer(args[0]));
            return true;
        }

        if (command.getLabel().equalsIgnoreCase("tpn")) {
            denyTP(sender, getServer().getPlayer(args[0]));
            return true;
        }

        return false;

    }

    public void clearOldRequests() {
        requestManager.clearOldRequests(configRequestTimeoutSeconds);
    }

    public void unvanish(Player player) {
        for (Player onlinePlayer : getServer().getOnlinePlayers()) {
            if (!onlinePlayer.equals(player)) {
                onlinePlayer.showPlayer(this, player);
            }
        }
    }

    private void askTP(Player tpTarget, Player tpRequester) {

        if (tpTarget == null || tpRequester == null) {
            return;
        }

        if (!configSpawnTpDeny && isAtSpawn(tpRequester)) {
            getLogger().info("Denying teleport request while in spawn area from " + tpRequester.getName() + " to " + tpTarget.getName());
            sendMessage(tpRequester, ChatColor.GOLD + "You are not allowed to teleport while in the spawn area!");
            return;
        }

        if (isToggled(tpTarget)) {
            sendMessage(tpRequester, tpTarget.getDisplayName() + ChatColor.GOLD + " is currently not accepting any teleport requests!");
            return;
        }

        if (requestManager.isRequestActive(tpTarget, tpRequester)) {
            sendMessage(tpRequester, ChatColor.GOLD + "Please wait for " + ChatColor.RESET + tpTarget.getDisplayName() + ChatColor.GOLD + " to accept or deny your request.");
            return;
        }

        if (!configMultiRequest && requestManager.isRequestActiveByRequester(tpRequester)) {
            sendMessage(tpRequester, ChatColor.GOLD + "Please wait for your existing request to be accepted or denied.");
            return;
        }

        sendMessage(tpRequester, ChatColor.GOLD + "Request sent to: " + ChatColor.RESET + tpTarget.getDisplayName());
        sendMessage(tpTarget, tpRequester.getDisplayName() + ChatColor.GOLD + " wants to teleport to you.");
        sendMessage(tpTarget, ChatColor.GOLD + "Type " + ChatColor.RESET + "/tpy " + tpRequester.getName() + ChatColor.GOLD + " to accept or " + ChatColor.RESET + "/tpn " + tpRequester.getName() + ChatColor.GOLD + " to deny.");

        requestManager.addRequest(tpTarget, tpRequester);

    }

    private void acceptTP(Player tpTarget, Player tpRequester) {

        if (tpTarget == null || tpRequester == null) {
            return;
        }

        if (!requestManager.isRequestActive(tpTarget, tpRequester)) {
            sendMessage(tpTarget, ChatColor.GOLD + "There is no request to accept from " + ChatColor.RESET + tpRequester.getDisplayName() + ChatColor.GOLD + "!");
            return;
        }

        sendMessage(tpTarget, ChatColor.GOLD + "Request from " + ChatColor.RESET + tpRequester.getDisplayName() + ChatColor.GREEN + " accepted" + ChatColor.GOLD + "!");
        sendMessage(tpRequester, ChatColor.GOLD + "Your request was " + ChatColor.GREEN + "accepted" + ChatColor.GOLD + ", teleporting to: " + ChatColor.RESET + tpTarget.getDisplayName());

        executeTP(tpTarget, tpRequester);
        requestManager.removeRequests(tpTarget, tpRequester);

    }

    private void denyTP(Player tpTarget, Player tpRequester) {

        if (tpTarget == null || tpRequester == null) {
            return;
        }

        if (!requestManager.isRequestActive(tpTarget, tpRequester)) {
            sendMessage(tpTarget, ChatColor.GOLD + "There is no request to deny from " + ChatColor.RESET + tpRequester.getDisplayName() + ChatColor.GOLD + "!");
            return;
        }

        sendMessage(tpTarget, ChatColor.GOLD + "Request from " + ChatColor.RESET + tpRequester.getDisplayName() + ChatColor.RED + " denied" + ChatColor.GOLD + "!");
        sendMessage(tpRequester, ChatColor.GOLD + "Your request sent to " + ChatColor.RESET + tpTarget.getDisplayName() + ChatColor.GOLD + " was" + ChatColor.RED + " denied" + ChatColor.GOLD + "!");
        requestManager.removeRequests(tpTarget, tpRequester);

    }

    private void executeTP(Player tpTarget, Player tpRequester) {

        if (tpTarget == null || tpRequester == null) {
            return;
        }

        getLogger().info("Teleporting " + tpRequester.getName() + " to " + tpTarget.getName());

        // dismount
        Optional<Entity> vehicle = Optional.ofNullable(tpRequester.getVehicle());
        if (vehicle.isPresent()) {
            getLogger().info("Dismounting " + tpRequester.getDisplayName() + " from " + vehicle.get().getName() + " before teleporting");
            vehicle.get().eject();
        }

        // vanish requester
        vanish(tpRequester);

        // execute teleport
        PaperLib.teleportAsync(tpRequester, tpTarget.getLocation()).thenAccept(result -> {
            if (result) {
                sendMessage(tpTarget, tpRequester.getDisplayName() + ChatColor.GOLD + " teleported to you!");
                sendMessage(tpRequester, ChatColor.GOLD + "Teleported to " + ChatColor.RESET + tpTarget.getDisplayName() + ChatColor.GOLD + "!");
            } else {
                sendMessage(tpTarget, ChatColor.RED + "Teleport failed, you should harass your admin because of this!");
                sendMessage(tpRequester, ChatColor.RED + "Teleport failed, you should harass your admin because of this!");
            }
        });

        // unvanish requester after n ticks
        new UnvanishRunnable(this, tpRequester).runTaskLater(this, configUnvanishDelay);

    }

    private void toggleRequestBlock(Player toggleRequester) {

        if (toggleRequester == null) {
            return;
        }

        if (isToggled(toggleRequester)) {
            getConfig().set(generateRequestBlockPath(toggleRequester), null); // if toggle is getting turned off, we delete instead of setting false
            sendMessage(toggleRequester, ChatColor.GOLD + "Request are now " + ChatColor.GREEN + " enabled" + ChatColor.GOLD + "!");
        } else {
            getConfig().set(generateRequestBlockPath(toggleRequester), true);
            requestManager.removeRequestsByTarget(toggleRequester);
            sendMessage(toggleRequester, ChatColor.GOLD + "Request are now " + ChatColor.RED + " disabled" + ChatColor.GOLD + "!");
        }

        saveConfig();

    }

    private String generateRequestBlockPath(Player player) {
        return "requests-blocked-" + player.getUniqueId().toString();
    }

    private boolean isToggled(Player player) {
        return getConfig().getBoolean(generateRequestBlockPath(player));
    }

    private boolean isInvalidTarget(String args) {

        // check for empty argument
        if (args.isEmpty()) {
            return true;
        }

        // check for invalid usernames
        String target = sanitizeUsername(args);
        if (target == null) {
            return true;
        }

        // check if player is online
        return getServer().getPlayer(target) == null;

    }

    private boolean isAtSpawn(Player requester) {

        Location loc = requester.getLocation();
        World.Environment dim = requester.getWorld().getEnvironment();

        // end spawn is not spawn
        if (dim.equals(World.Environment.THE_END)) {
            return false;
        }

        boolean isNether = dim.equals(World.Environment.NETHER);

        return Math.abs(loc.getX()) * (isNether ? 8 : 1) <= configSpawnTpDenyRadius && Math.abs(loc.getZ()) * (isNether ? 8 : 1) <= configSpawnTpDenyRadius;

    }

    private String sanitizeUsername(String name) {

        name = name.replaceAll("[^a-zA-Z0-9_]", "");

        if (name.length() < 3 || name.length() > 16) {
            return null;
        }

        return name;

    }

    private void vanish(Player player) {
        for (Player onlinePlayer : getServer().getOnlinePlayers()) {
            if (!onlinePlayer.equals(player)) {
                onlinePlayer.hidePlayer(this, player);
            }
        }
    }

    private void loadConfig() {

        getConfig().addDefault("allow-multi-target-request", true);
        getConfig().addDefault("request-timeout-seconds", 60);
        getConfig().addDefault("unvanish-delay-ticks", 20);
        getConfig().addDefault("spawn-tp-deny", true);
        getConfig().addDefault("spawn-tp-deny-radius", 1500);

        getConfig().options().copyDefaults(true);

        saveConfig();

        // rename deprecated config paths
        renameConfigPathIfPresent("allow-tp-from-spawn", "spawn-tp-deny");
        renameConfigPathIfPresent("spawn-radius", "spawn-tp-deny-radius");

        configMultiRequest = getConfig().getBoolean("allow-multi-target-request");
        configRequestTimeoutSeconds = sanitizeConfigIntValue("request-timeout-seconds");
        configUnvanishDelay = sanitizeConfigIntValue("unvanish-delay-ticks");
        configSpawnTpDeny = getConfig().getBoolean("spawn-tp-deny");
        configSpawnTpDenyRadius = sanitizeConfigIntValue("spawn-tp-deny-radius");

    }

    private void renameConfigPathIfPresent(String oldPath, String newPath) {
        if (Optional.ofNullable(getConfig().get(oldPath)).isPresent()) {
            getConfig().set(newPath, getConfig().getBoolean(oldPath));
            getConfig().set(oldPath, null);
            saveConfig();
            getLogger().info("Converted old config path " + "\"" + oldPath + "\"" + " to " + "\"" + newPath + "\"");
        }
    }

}
