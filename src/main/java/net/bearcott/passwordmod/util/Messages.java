package net.bearcott.passwordmod.util;

public class Messages {
    public static final String LOGIN_PROMPT_DIV = "§b§m---------------------------------------";
    public static final String LOGIN_PROMPT_LINE = "§fType §e/login <password> §fin chat to join!";
    public static final String TIMEOUT_DISCONNECT = "§cHello? Were you asleep? I kick you!";
    public static final String RATE_LIMITED = "§cTry again in a moment...";
    public static final String FATAL_ERROR = "§cUh Oh! Something went wrong, please contact an admin...";

    public static String authSuccess(String name) {
        return "✅ **" + name + "** authenticated successfully.";
    }

    public static String timeoutBroadcast(String name) {
        return "⏰ **" + name + "** timed out.";
    }

    public static String terminatedDisconnect(String where) {
        return "§4§lTERMINATED. §cGo back to " + where + "! You are not welcome! Go touch grass!";
    }

    public static String disconnectFailed(String name) {
        return "👋 **" + name + "** disconnected (in waiting room).";
    }

    public static String disconnectLeft(String name) {
        return "🔌 **" + name + "** left.";
    }

    public static final String SERVER_ONLINE_TITLE = "🟢 **Server Online**";
    public static final String SERVER_STARTED_DESC = "The server has started successfully and is ready for connections.";
    public static final String SERVER_STOPPING_TITLE = "🛑 **Server Stopping**";
    public static final String SERVER_STOPPING_DESC = "The server is shutting down...";
    public static final String SERVER_CRASHED_TITLE = "☠️ **Server Crashed / Killed**";
    public static final String SERVER_CRASHED_DESC = "The server process terminated unexpectedly!";
}
