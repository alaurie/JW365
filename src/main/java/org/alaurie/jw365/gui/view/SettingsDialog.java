package org.alaurie.jw365.gui.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.alaurie.jw365.config.AppVersion;
import org.alaurie.jw365.config.ClientConfig;
import org.alaurie.jw365.gui.state.AppState;
import org.alaurie.jw365.rdp.FreeRdpInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Settings configuration dialog for FreeRDP parameters, display scaling, browser preferences,
 * and Entra ID tenant preferences.
 */
public final class SettingsDialog extends Stage {

    private final AppState state;

    private final TextField tenantField;
    private final ChoiceBox<String> browserChoice;
    private final ChoiceBox<String> freerdpSourceChoice;
    private final TextField customRdpPathField;
    private final ChoiceBox<String> scalingChoice;
    private final CheckBox fullscreenCheck;
    private final CheckBox multiMonCheck;
    private final CheckBox soundCheck;
    private final CheckBox micCheck;
    private final CheckBox usbCheck;
    private final CheckBox smartcardCheck;
    private final CheckBox ignoreCertCheck;
    private final CheckBox clipboardCheck;
    private final CheckBox dynamicResCheck;
    private final CheckBox gfxProgressiveCheck;
    private final CheckBox asyncUpdateCheck;
    private final CheckBox autoReconnectCheck;

    private final CheckBox autoConnectCheck;

    private final TextField autoRefreshField;
    private final TextField extraArgsField;

    public SettingsDialog(Window owner, AppState state) {
        this.state = state;

        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle("JW365 Settings (v" + AppVersion.VERSION + ")");
        setMinWidth(580);
        setMinHeight(680);

        ClientConfig currentConfig = state.getConfigManager().get();

        BorderPane root = new BorderPane();
        root.getStyleClass().add("dialog-container");

        VBox contentBox = new VBox(16);
        contentBox.setPadding(new Insets(16));

        // 1. Entra ID / Tenant Section
        Label tenantSection = new Label("Microsoft Entra ID & Authentication");
        tenantSection.getStyleClass().add("brand-title");

        GridPane tenantGrid = new GridPane();
        tenantGrid.setHgap(12);
        tenantGrid.setVgap(8);

        Label tenantLabel = new Label("Default Tenant:");
        tenantLabel.getStyleClass().add("form-label");
        tenantField = new TextField(currentConfig.defaultTenant());
        tenantField.setPromptText("organizations (or specific tenant UUID/domain)");
        GridPane.setHgrow(tenantField, Priority.ALWAYS);

        tenantGrid.addRow(0, tenantLabel, tenantField);

        Label browserLabel = new Label("Auth Browser:");
        browserLabel.getStyleClass().add("form-label");

        browserChoice = new ChoiceBox<>();
        browserChoice.getItems().addAll(
            "Microsoft Edge (Recommended for M365 SSO)",
            "System Default Browser (xdg-open)",
            "Embedded In-App WebView",
            "Google Chrome",
            "Mozilla Firefox"
        );
        browserChoice.setValue(browserCodeToLabel(currentConfig.preferredBrowser()));

        tenantGrid.addRow(1, browserLabel, browserChoice);

        // 2. FreeRDP Binary Section
        Label rdpSection = new Label("FreeRDP Client Engine");
        rdpSection.getStyleClass().add("brand-title");

        FreeRdpInfo detected = state.detectedFreeRdpProperty().get();
        Label detectedLabel = new Label("Detected: " + (detected != null ? detected.displayName() : "None found (please install FreeRDP)"));
        detectedLabel.getStyleClass().add("status-bar-text");
        Label sourceLabel = new Label("Source:");
        sourceLabel.getStyleClass().add("form-label");
        freerdpSourceChoice = new ChoiceBox<>();
        freerdpSourceChoice.getItems().addAll("Automatic (Flatpak first)", "System FreeRDP", "Flatpak FreeRDP", "Custom executable");
        freerdpSourceChoice.setValue(sourceCodeToLabel(currentConfig.freerdpSource()));
        HBox sourceBox = new HBox(12, sourceLabel, freerdpSourceChoice);
        sourceBox.setAlignment(Pos.CENTER_LEFT);

        HBox customRdpBox = new HBox(8);
        customRdpPathField = new TextField(currentConfig.preferredFreeRdpPath() != null ? currentConfig.preferredFreeRdpPath() : "");
        customRdpPathField.setPromptText("Path to custom sdl-freerdp/xfreerdp binary");
        HBox.setHgrow(customRdpPathField, Priority.ALWAYS);

        Button browseBtn = new Button("Browse...");
        browseBtn.getStyleClass().add("btn-secondary");
        browseBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select FreeRDP Executable");
            File file = chooser.showOpenDialog(this);
            if (file != null) {
                customRdpPathField.setText(file.getAbsolutePath());
            }
        });
        customRdpBox.getChildren().addAll(customRdpPathField, browseBtn);

        // 3. Display & Performance Section
        Label displaySection = new Label("Display & Performance");
        displaySection.getStyleClass().add("brand-title");

        GridPane displayGrid = new GridPane();
        displayGrid.setHgap(12);
        displayGrid.setVgap(8);

        Label scaleLabel = new Label("Desktop Scaling:");
        scaleLabel.getStyleClass().add("form-label");

        scalingChoice = new ChoiceBox<>();
        scalingChoice.getItems().addAll("Auto (100%)", "100%", "125%", "150%", "175%", "200%", "250%", "300%");
        scalingChoice.setValue(scalePercentToString(currentConfig.scalePercent()));

        displayGrid.addRow(0, scaleLabel, scalingChoice);

        fullscreenCheck = new CheckBox("Launch in Fullscreen Mode (/f)");
        fullscreenCheck.setSelected(currentConfig.fullscreen());

        multiMonCheck = new CheckBox("Use Multiple Monitors if available (/multimon)");
        Label multiMonHint = new Label("Multi-monitor launches fullscreen. Right Ctrl + F10 toggles fullscreen; Right Ctrl + F12 disconnects.");
        multiMonHint.getStyleClass().add("status-bar-text");
        multiMonCheck.setSelected(currentConfig.multiMonitor());

        dynamicResCheck = new CheckBox("Dynamic Desktop Resizing (+dynamic-resolution)");
        dynamicResCheck.setSelected(currentConfig.dynamicResolution());

        gfxProgressiveCheck = new CheckBox("H.264 / RDP8 Progressive Graphics Acceleration (/gfx:progressive)");
        gfxProgressiveCheck.setSelected(currentConfig.gfxProgressive());

        asyncUpdateCheck = new CheckBox("Asynchronous Rendering & Network Channel I/O (+async-update)");
        asyncUpdateCheck.setSelected(currentConfig.asyncUpdate());

        autoReconnectCheck = new CheckBox("Automatic Reconnection on Network Interruption (+auto-reconnect)");
        autoReconnectCheck.setSelected(currentConfig.autoReconnect());
        usbCheck = new CheckBox("Redirect USB devices (/usb:auto)");
        usbCheck.setSelected(currentConfig.usbRedirection());
        smartcardCheck = new CheckBox("Redirect smartcard (/smartcard)");
        smartcardCheck.setSelected(currentConfig.smartcard());

        clipboardCheck = new CheckBox("Bidirectional Clipboard Synchronization (+clipboard)");
        clipboardCheck.setSelected(currentConfig.clipboard());

        soundCheck = new CheckBox("Redirect Audio Output (/sound:sys:pulse)");
        soundCheck.setSelected(currentConfig.sound());

        micCheck = new CheckBox("Redirect Microphone Input (/microphone)");
        micCheck.setSelected(currentConfig.microphone());

        ignoreCertCheck = new CheckBox("Ignore SSL Certificate Warnings (/cert:ignore)");
        ignoreCertCheck.setSelected(currentConfig.ignoreCert());

        // 4. Automation Section
        Label advancedSection = new Label("Automation & Preferences");
        advancedSection.getStyleClass().add("brand-title");

        autoConnectCheck = new CheckBox("Automatically connect to primary Cloud PC on launch");
        autoConnectCheck.setSelected(currentConfig.autoConnect());

        GridPane advGrid = new GridPane();
        advGrid.setHgap(12);
        advGrid.setVgap(8);

        Label autoRefreshLabel = new Label("Auto Refresh (min):");
        autoRefreshLabel.getStyleClass().add("form-label");
        autoRefreshField = new TextField(String.valueOf(currentConfig.autoRefreshMinutes()));
        autoRefreshField.setPrefWidth(80);

        advGrid.addRow(0, autoRefreshLabel, autoRefreshField);

        Label extraArgsLabel = new Label("Extra FreeRDP Args:");
        extraArgsLabel.getStyleClass().add("form-label");
        extraArgsField = new TextField(String.join(" ", currentConfig.extraArgs()));
        GridPane.setHgrow(extraArgsField, Priority.ALWAYS);

        advGrid.addRow(1, extraArgsLabel, extraArgsField);

        contentBox.getChildren().addAll(
            tenantSection, tenantGrid,
            new Separator(),
            rdpSection, detectedLabel, sourceBox, customRdpBox,
            new Separator(),
            displaySection, displayGrid,
            fullscreenCheck, multiMonCheck, multiMonHint, dynamicResCheck, gfxProgressiveCheck, asyncUpdateCheck, autoReconnectCheck, usbCheck, smartcardCheck, clipboardCheck, soundCheck, micCheck, ignoreCertCheck,
            new Separator(),
            advancedSection, autoConnectCheck, advGrid
        );

        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        root.setCenter(scrollPane);

        // Bottom Action Buttons
        HBox buttonBar = new HBox(12);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(12, 16, 12, 16));

        Label versionInfo = new Label("JW365 Client v" + AppVersion.VERSION);
        versionInfo.getStyleClass().add("status-bar-text");
        HBox.setHgrow(versionInfo, Priority.ALWAYS);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setOnAction(e -> close());

        Button saveBtn = new Button("Save Settings");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> handleSave());

        buttonBar.getChildren().addAll(versionInfo, cancelBtn, saveBtn);
        root.setBottom(buttonBar);

        Scene scene = new Scene(root, 600, 700);
        scene.getStylesheets().add(Objects.requireNonNull(
            getClass().getResource("/org/alaurie/jw365/gui/styles.css"),
            "Missing stylesheet resource"
        ).toExternalForm());
        setScene(scene);
    }

    private void handleSave() {
        String tenant = tenantField.getText() == null ? "" : tenantField.getText().trim();
        if (tenant.isBlank()) {
            showValidationError("Default tenant cannot be empty.");
            return;
        }

        int scale = parseScaleString(scalingChoice.getValue());
        int autoRefresh;
        try {
            autoRefresh = Integer.parseInt(autoRefreshField.getText().trim());
        } catch (NumberFormatException e) {
            showValidationError("Auto refresh must be a whole number between 5 and 1440 minutes.");
            return;
        }
        if (autoRefresh < 5 || autoRefresh > 1440) {
            showValidationError("Auto refresh must be between 5 and 1440 minutes.");
            return;
        }
        List<String> extraArgs = new ArrayList<>();
        String rawExtra = extraArgsField.getText();
        if (rawExtra != null && !rawExtra.isBlank()) {
            extraArgs.addAll(Arrays.stream(rawExtra.split("\\s+"))
                .filter(s -> !s.isBlank())
                .toList());
        }

        String customPath = customRdpPathField.getText().trim();
        if (!customPath.isBlank()) {
            try {
                if (!Files.isExecutable(Path.of(customPath))) {
                    showValidationError("Selected FreeRDP path is not executable.");
                    return;
                }
            } catch (RuntimeException e) {
                showValidationError("Selected FreeRDP path is invalid.");
                return;
            }
        } else {
            customPath = null;
        }

        String preferredBrowser = labelToBrowserCode(browserChoice.getValue());

        ClientConfig newConfig = new ClientConfig(
            tenant,
            browserCodeToSource(freerdpSourceChoice.getValue()),
            customPath,
            preferredBrowser,
            scale,
            fullscreenCheck.isSelected(),
            soundCheck.isSelected(),
            micCheck.isSelected(),
            multiMonCheck.isSelected(),
            ignoreCertCheck.isSelected(),
            clipboardCheck.isSelected(),
            dynamicResCheck.isSelected(),
            gfxProgressiveCheck.isSelected(),
            asyncUpdateCheck.isSelected(),
            autoReconnectCheck.isSelected(),
            autoConnectCheck.isSelected(),
            usbCheck.isSelected(),
            smartcardCheck.isSelected(),
            autoRefresh,
            extraArgs
        );

        state.updateConfig(newConfig);
        close();
    }

    private static String sourceCodeToLabel(String source) {
        if ("SYSTEM".equalsIgnoreCase(source)) return "System FreeRDP";
        if ("FLATPAK".equalsIgnoreCase(source)) return "Flatpak FreeRDP";
        if ("CUSTOM".equalsIgnoreCase(source)) return "Custom executable";
        return "Automatic (Flatpak first)";
    }

    private static String browserCodeToSource(String label) {
        if (label != null && label.startsWith("System")) return "SYSTEM";
        if (label != null && label.startsWith("Flatpak")) return "FLATPAK";
        if (label != null && label.startsWith("Custom")) return "CUSTOM";
        return "AUTO";
    }

    private void showValidationError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("Invalid Settings");
        alert.setHeaderText("Check your settings");
        alert.showAndWait();
    }

    private static String browserCodeToLabel(String code) {
        if (code == null || code.equalsIgnoreCase("EDGE")) return "Microsoft Edge (Recommended for M365 SSO)";
        if (code.equalsIgnoreCase("CHROME")) return "Google Chrome";
        if (code.equalsIgnoreCase("FIREFOX")) return "Mozilla Firefox";
        if (code.equalsIgnoreCase("WEBVIEW")) return "Embedded In-App WebView";
        return "System Default Browser (xdg-open)";
    }

    private static String labelToBrowserCode(String label) {
        if (label == null) return "EDGE";
        if (label.contains("Edge")) return "EDGE";
        if (label.contains("Chrome")) return "CHROME";
        if (label.contains("Firefox")) return "FIREFOX";
        if (label.contains("WebView")) return "WEBVIEW";
        return "DEFAULT";
    }

    private static String scalePercentToString(int scale) {
        if (scale <= 0 || scale == 100) return "100%";
        return scale + "%";
    }

    private static int parseScaleString(String s) {
        if (s == null || s.contains("Auto") || s.contains("100%")) return 0;
        try {
            return Integer.parseInt(s.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
