package net.bearcott.passwordmod.util;

/**
 * All human-readable text used by the mod. Edit strings here to re-word
 * anything players
 * or admins see. Strings ending in {@code _FMT} are {@link String#format}
 * templates — check
 * the adjacent comment for the expected argument order.
 */
public class Messages {

    // ============ Login UI (shown to unauthenticated players) ============

    public static final String LOGIN_PROMPT_DIV = "§b§m---------------------------------------";
    public static final String LOGIN_PROMPT_LINE = "§fType §e/login <password> §fin chat to join!";
    public static final String LOGIN_ACTION_BAR = "§eType §f/login <password> §eto join";

    // Defaults for login_title / login_description when the config file doesn't set
    // them.
    public static final String LOGIN_DEFAULT_TITLE = "§6Welcome and Incredible!";
    public static final String LOGIN_DEFAULT_DESCRIPTION = "§7Identify yourself or perish.";

    // ============ /login feedback ============

    public static final String AUTHENTICATED = "§a§lAuthenticated!§r May GulaGod bless you.";
    public static final String RATE_LIMITED = "§cTry again in a moment...";
    public static final String FATAL_ERROR = "§cUh Oh! Something went wrong, please contact an admin...";
    public static final String LOCKDOWN_DENIED = "§cNope! :3";
    public static final String DUPLICATE_LOGIN_DENIED = "§cSomeone is already logged into this account. Try again later.";

    // ============ Kick messages ============

    public static final String KICK_TIMEOUT = "§cHello? Were you asleep? I kick you!";
    // (city-or-region)
    public static final String KICK_TERMINATED_FMT = "§4§lTERMINATED. §cGo back to %s! You are not welcome! Go touch grass!";

    // (name, opLevel)
    public static final String WELCOME_BACK_FMT = "§b[Security]§r Welcome back comrade %s! Lvl %d OP restored.";

    // ============ Public Discord webhook (webhook_url) ============

    // (name)
    public static final String WEBHOOK_JOIN_WHITELISTED_FMT = "⚠️ **%s** joined (Whitelisted)";
    public static final String WEBHOOK_JOIN_NEW_FMT = "⚠️ **%s** joined (New Session)";
    public static final String WEBHOOK_AUTH_SUCCESS_FMT = "✅ **%s** authenticated successfully.";
    public static final String WEBHOOK_TIMEOUT_FMT = "⏰ **%s** timed out.";
    public static final String WEBHOOK_DISCONNECT_PENDING_FMT = "👋 **%s** disconnected (in waiting room).";
    public static final String WEBHOOK_DISCONNECT_LEFT_FMT = "🔌 **%s** left.";

    // (name, attempt, max, tried_input)
    public static final String WEBHOOK_FAILED_ATTEMPT_FMT = "❌ **%s** failed (%d/%d). Tried: `%s`";

    // (name, advancement_title)
    public static final String WEBHOOK_ADVANCEMENT_AUTHORIZED_FMT = "🎖️ **%s** just made the advancement `%s`";
    public static final String WEBHOOK_ADVANCEMENT_UNAUTHORIZED_FMT = "⚠️ [UNAUTHORIZED!] **%s** just made the advancement `%s`";

    // (ip, location-string); appended after any broadcast that includes an IP.
    public static final String WEBHOOK_IP_SUFFIX_FMT = "\n📍 IP: `%s` (%s)";

    // ============ Server status (admin_webhook_url) ============

    public static final String SERVER_ONLINE_TITLE = "🟢 **Server Online**";
    public static final String SERVER_STARTED_DESC = "The server has started successfully and is ready for connections.";
    public static final String SERVER_STOPPING_TITLE = "🛑 **Server Stopping**";
    public static final String SERVER_STOPPING_DESC = "The server is shutting down...";
    public static final String SERVER_CRASHED_TITLE = "☠️ **Server Crashed / Killed**";
    public static final String SERVER_CRASHED_DESC = "The server process terminated unexpectedly!";
}
