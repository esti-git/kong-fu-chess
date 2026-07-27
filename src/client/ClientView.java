package client;

import audio.SoundManager;
import client.logging.ClientLog;
import enums.PieceColor;
import events.Event;
import events.EventBus;
import events.GameEndedEvent;
import events.GameStartedEvent;
import events.MoveMadeEvent;
import events.PieceCapturedEvent;
import protocol.AssignedIdentity;
import protocol.LoginResult;
import protocol.NetworkState;
import protocol.RoomJoined;
import protocol.SpectateInfo;
import view.BoardRenderer;
import view.BoardSnapshot;
import view.GameAnimationController;
import view.Img;
import view.MoveHistoryTracker;
import view.ScaledImagePanel;
import view.ScoreTracker;
import view.SidePanelFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class ClientView {

    private static final Color FRAME_BACKGROUND = SidePanelFactory.FRAME_BACKGROUND;
    private static final Color PANEL_ACCENT = SidePanelFactory.PANEL_ACCENT;
    private static final Font TITLE_FONT = SidePanelFactory.TITLE_FONT;

    private final BoardRenderer renderer = new BoardRenderer();
    private final ScaledImagePanel boardPanel = new ScaledImagePanel();
    private final EventBus eventBus = new EventBus();
    private final MoveHistoryTracker historyTracker = new MoveHistoryTracker(eventBus);
    private final ScoreTracker scoreTracker = new ScoreTracker(eventBus);
    private final SoundManager soundManager = new SoundManager(eventBus);
    private final GameAnimationController animationController = new GameAnimationController(eventBus);

    private GameClient client;
    private volatile BoardSnapshot latestSnapshot;
    private final BoardInputHandler inputHandler = new BoardInputHandler();

    private PieceColor myColor;
    private String whiteName;
    private String blackName;
    private int whiteRating = 1200;
    private int blackRating = 1200;
    private volatile boolean gameOver;
    private boolean disconnectNotified;

    private boolean spectator;
    private String roomId;
    private JLabel roomLabel;
    private JButton playAgainButton;

    private JDialog countdownDialog;
    private JLabel countdownLabel;
    private Timer countdownTimer;
    private int countdownSecondsLeft;

    private JFrame guiWindow;
    private JPanel westPanel;
    private JPanel eastPanel;
    private TitledBorder whiteBorder;
    private TitledBorder blackBorder;
    private JTextArea whiteMovesArea;
    private JTextArea blackMovesArea;
    private JLabel whiteScoreLabel;
    private JLabel blackScoreLabel;
    private int baselineWidth;
    private int baselineHeight;

    public ClientView() {
        eventBus.subscribe(MoveMadeEvent.TYPE, event -> refreshHistoryPanelsOnEdt());
        eventBus.subscribe(PieceCapturedEvent.TYPE, event -> refreshHistoryPanelsOnEdt());
        eventBus.subscribe(GameStartedEvent.TYPE, event -> SwingUtilities.invokeLater(() -> {
            gameOver = false;
            disconnectNotified = false;
            if (playAgainButton != null) playAgainButton.setVisible(false);
            repaintBoard();
        }));
        eventBus.subscribe(GameEndedEvent.TYPE, event -> SwingUtilities.invokeLater(() -> {
            gameOver = true;
            if (playAgainButton != null && !spectator) playAgainButton.setVisible(true);
            repaintBoard();
        }));
    }

    public void setClient(GameClient client) {
        this.client = client;
    }

    public void show() {
        SwingUtilities.invokeLater(() -> {
            guiWindow = new JFrame("Kung Fu Chess - Client");
            guiWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            boardPanel.setBackground(FRAME_BACKGROUND);

            whiteMovesArea = SidePanelFactory.createMovesArea();
            blackMovesArea = SidePanelFactory.createMovesArea();
            whiteScoreLabel = SidePanelFactory.createScoreLabel("Score: 0");
            blackScoreLabel = SidePanelFactory.createScoreLabel("Score: 0");
            whiteBorder = SidePanelFactory.createTitledBorder("White");
            blackBorder = SidePanelFactory.createTitledBorder("Black");

            westPanel = SidePanelFactory.buildSidePanel(whiteBorder, whiteMovesArea, whiteScoreLabel);
            eastPanel = SidePanelFactory.buildSidePanel(blackBorder, blackMovesArea, blackScoreLabel);

            roomLabel = new JLabel(" ", SwingConstants.CENTER);
            roomLabel.setFont(TITLE_FONT);
            roomLabel.setForeground(PANEL_ACCENT);
            roomLabel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

            playAgainButton = new JButton("Play Again");
            playAgainButton.setFont(playAgainButton.getFont().deriveFont(Font.BOLD, 16f));
            playAgainButton.setVisible(false);
            playAgainButton.addActionListener(e -> {
                if (client != null) client.sendPlayAgain();
                playAgainButton.setVisible(false);
            });
            JPanel southPanel = new JPanel(new FlowLayout());
            southPanel.add(playAgainButton);

            guiWindow.add(roomLabel, BorderLayout.NORTH);
            guiWindow.add(westPanel, BorderLayout.WEST);
            guiWindow.add(boardPanel, BorderLayout.CENTER);
            guiWindow.add(eastPanel, BorderLayout.EAST);
            guiWindow.add(southPanel, BorderLayout.SOUTH);

            boardPanel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    java.awt.Point imagePoint = boardPanel.panelToImage(e.getX(), e.getY());
                    if (imagePoint == null) return;

                    if (e.getClickCount() >= 2) {
                        handleDoubleClick(imagePoint.x, imagePoint.y);
                    } else {
                        handleClick(imagePoint.x, imagePoint.y);
                    }
                }
            });

            guiWindow.setMinimumSize(new Dimension(500, 400));
            guiWindow.pack();
            guiWindow.setLocationRelativeTo(null);

            baselineWidth = guiWindow.getWidth();
            baselineHeight = guiWindow.getHeight();
            guiWindow.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    rescaleSidePanels();
                }
            });

            guiWindow.setVisible(true);
            repaintBoard();
            updateHistoryPanels();
            applyIdentityLabels();
        });
    }

    public void onState(NetworkState state) {
        this.latestSnapshot = state.snapshot;
        SwingUtilities.invokeLater(this::repaintBoard);
    }

    public void onError(String message) {
        ClientLog.warn("Server rejected command: " + message);
    }

    public void onEvent(Event event) {
        eventBus.publish(event);
    }

    public void onHistory(java.util.List<Event> events) {
        for (Event event : events) {
            if (MoveMadeEvent.TYPE.equals(event.getType())) {
                historyTracker.applyMoveMade(event);
            } else if (PieceCapturedEvent.TYPE.equals(event.getType())) {
                scoreTracker.applyPieceCaptured(event);
            }
        }
        refreshHistoryPanelsOnEdt();
    }

    public void onAssign(AssignedIdentity identity) {
        this.myColor = identity.color;
        this.whiteName = identity.whiteName;
        this.blackName = identity.blackName;
        this.whiteRating = identity.whiteRating;
        this.blackRating = identity.blackRating;
        SwingUtilities.invokeLater(() -> {
            dismissDisconnectCountdown();
            applyIdentityLabels();
        });
    }

    public void onRoomJoined(RoomJoined joined) {
        this.roomId = joined.roomId;
        SwingUtilities.invokeLater(this::applyIdentityLabels);
    }

    public void onSpectate(SpectateInfo info) {
        this.spectator = true;
        this.myColor = null;
        this.whiteName = info.whiteName;
        this.blackName = info.blackName;
        this.whiteRating = info.whiteRating;
        this.blackRating = info.blackRating;
        SwingUtilities.invokeLater(() -> {
            dismissDisconnectCountdown();
            applyIdentityLabels();
        });
    }

    public void onDisconnectCountdown(int seconds) {
        SwingUtilities.invokeLater(() -> {
            countdownSecondsLeft = seconds;
            if (countdownDialog == null) {
                countdownDialog = new JDialog(guiWindow, "Opponent disconnected", false);
                countdownLabel = new JLabel("", SwingConstants.CENTER);
                countdownLabel.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
                countdownDialog.add(countdownLabel);
                countdownDialog.setMinimumSize(new Dimension(320, 100));
                countdownDialog.setLocationRelativeTo(guiWindow);
            }
            updateCountdownLabel();
            countdownDialog.setVisible(true);

            if (countdownTimer != null) countdownTimer.stop();
            countdownTimer = new Timer(1000, e -> {
                countdownSecondsLeft--;
                if (countdownSecondsLeft <= 0) {
                    dismissDisconnectCountdown();
                } else {
                    updateCountdownLabel();
                }
            });
            countdownTimer.start();
        });
    }

    private void updateCountdownLabel() {
        if (countdownLabel != null) {
            countdownLabel.setText("Opponent disconnected. Auto-resign in " + countdownSecondsLeft + "s...");
        }
    }

    private void dismissDisconnectCountdown() {
        if (countdownTimer != null) {
            countdownTimer.stop();
            countdownTimer = null;
        }
        if (countdownDialog != null) {
            countdownDialog.setVisible(false);
        }
    }

    public void onRejected(String message) {
        ClientLog.warn("Join rejected: " + message);
        System.exit(0);
    }

    public void onLoginResult(LoginResult result) {
        if (!result.success) {
            onRejected(result.message);
            return;
        }
        ClientLog.info("Logged in. Rating: " + result.rating);
    }

    public void onOpponentDisconnected(String message) {
        gameOver = true;
        ClientLog.info("Opponent disconnected: " + message);
        showGameOverOnce(message);
    }

    public void onConnectionClosed(String reason) {
        gameOver = true;
        ClientLog.info("Connection closed: " + reason);
        showGameOverOnce((reason == null || reason.isBlank()) ? "Connection closed. Game over." : reason);
    }

    private void showGameOverOnce(String message) {
        SwingUtilities.invokeLater(() -> {
            dismissDisconnectCountdown();
            if (disconnectNotified) return;
            disconnectNotified = true;
            if (guiWindow != null) {
                JOptionPane.showMessageDialog(guiWindow, message, "Game Over", JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    private void applyIdentityLabels() {
        if (guiWindow == null || whiteBorder == null || blackBorder == null) return;

        String white = whiteName == null ? "waiting..." : whiteName + " (" + whiteRating + ")";
        String black = blackName == null ? "waiting..." : blackName + " (" + blackRating + ")";
        whiteBorder.setTitle("White - " + white);
        blackBorder.setTitle("Black - " + black);

        if (spectator) {
            guiWindow.setTitle("Kung Fu Chess - Spectating - " + white + " vs " + black);
        } else if (myColor != null) {
            String you = myColor == PieceColor.WHITE ? "White" : "Black";
            String yourName = myColor == PieceColor.WHITE ? white : black;
            guiWindow.setTitle("Kung Fu Chess - " + you + " - " + yourName);
        }

        if (roomLabel != null) {
            roomLabel.setText(roomId == null ? " " : "Room: " + roomId + (spectator ? "  (spectating)" : ""));
        }

        westPanel.repaint();
        eastPanel.repaint();
    }

    private void refreshHistoryPanelsOnEdt() {
        SwingUtilities.invokeLater(this::updateHistoryPanels);
    }

    private void repaintBoard() {
        BoardSnapshot snapshot = latestSnapshot;
        if (snapshot == null) return;
        Img visualBoard = renderer.render(snapshot, inputHandler.getSelected());
        if (animationController.isShowingGameOverOverlay()) {
            PieceColor winner = animationController.getWinnerColor();
            String winnerText = winner == null ? null : (winner == PieceColor.WHITE ? "White" : "Black") + " wins!";
            SidePanelFactory.drawGameOverOverlay(visualBoard.get(), "GAME OVER", winnerText);
        }
        boardPanel.setImage(visualBoard.get());
        boardPanel.repaint();
    }

    private void handleClick(int pixelX, int pixelY) {
        inputHandler.handleClick(client, latestSnapshot, gameOver, spectator, pixelX, pixelY, this::repaintBoard);
    }

    private void handleDoubleClick(int pixelX, int pixelY) {
        inputHandler.handleDoubleClick(client, latestSnapshot, gameOver, spectator, pixelX, pixelY, this::repaintBoard);
    }

    private void rescaleSidePanels() {
        SidePanelFactory.rescaleSidePanels(guiWindow, baselineWidth, baselineHeight,
                westPanel, eastPanel, whiteMovesArea, blackMovesArea,
                whiteScoreLabel, blackScoreLabel, whiteBorder, blackBorder);
    }

    private void updateHistoryPanels() {
        if (whiteMovesArea == null || blackMovesArea == null) return;
        whiteMovesArea.setText(String.join("\n", historyTracker.getWhiteMoves()));
        blackMovesArea.setText(String.join("\n", historyTracker.getBlackMoves()));
        whiteMovesArea.setCaretPosition(whiteMovesArea.getDocument().getLength());
        blackMovesArea.setCaretPosition(blackMovesArea.getDocument().getLength());
        whiteScoreLabel.setText("Score: " + scoreTracker.getWhiteScore());
        blackScoreLabel.setText("Score: " + scoreTracker.getBlackScore());
    }
}
