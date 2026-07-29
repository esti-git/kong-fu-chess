package client;

import client.logging.ClientLog;
import config.GameConfig;

import org.json.JSONObject;

import java.net.URI;
import java.util.Scanner;

public class ClientMain {
    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : GameConfig.DEFAULT_WS_PORT;
        String apiGatewayUrl = args.length > 1 ? args[1] : GameConfig.DEFAULT_HTTP_PORT;

        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter username: ");
        String username = scanner.nextLine().trim();
        if (username.isEmpty()) {
            username = "Player";
        }

        System.out.print("Enter password: ");
        String password = scanner.nextLine();

        JSONObject gatewayResult = new ApiGatewayClient(apiGatewayUrl).login(username, password);
        if (gatewayResult == null) {
            ClientLog.warn("Falling back to WebSocket-only login (API Gateway unreachable)");
        } else if (!gatewayResult.optBoolean("success", false)) {
            System.out.println("Login failed: " + gatewayResult.optString("message", "unknown error"));
            return;
        }

        ClientView view = new ClientView();
        HomeView home = new HomeView();
        boolean[] boardShown = {false};
        Runnable showBoardOnce = () -> {
            home.close();
            if (!boardShown[0]) {
                boardShown[0] = true;
                view.show();
            }
        };

        GameClient client = new GameClient(new URI(url), username, password, view::onState, view::onError, view::onEvent,
                identity -> {
                    showBoardOnce.run();
                    view.onAssign(identity);
                },
                view::onRejected, view::onOpponentDisconnected, view::onConnectionClosed,
                result -> {
                    view.onLoginResult(result);
                    if (result.success && !result.reconnected) home.show();
                },
                home::onSeekTimeout, view::onDisconnectCountdown,
                view::onRoomJoined, home::onRoomError,
                info -> {
                    showBoardOnce.run();
                    view.onSpectate(info);
                },
                view::onHistory);
        view.setClient(client);

        home.setOnPlay(() -> {
            client.sendSeek();
            home.showSearching();
        });
        home.setOnCreateRoom(roomName -> {
            client.sendCreateRoom(roomName);
            home.showWaitingForRoom(roomName.isEmpty() ? "Creating room..." : "Creating room \"" + roomName + "\"...");
        });
        home.setOnJoinRoom(roomId -> {
            client.sendJoinRoom(roomId);
            home.showWaitingForRoom("Joining room " + roomId + "...");
        });

        client.connectBlocking();
    }
}
