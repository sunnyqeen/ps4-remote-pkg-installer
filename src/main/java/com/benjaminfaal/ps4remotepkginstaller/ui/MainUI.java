package com.benjaminfaal.ps4remotepkginstaller.ui;

import com.benjaminfaal.ps4errorcodes.PS4ErrorCode;
import com.benjaminfaal.ps4errorcodes.PS4ErrorCodes;
import com.benjaminfaal.ps4remotecontrol.companionapp.PS4CompanionAppConnection;
import com.benjaminfaal.ps4remotecontrol.companionapp.packet.response.LoginResponse;
import com.benjaminfaal.ps4remotecontrol.ddp.PS4DDP;
import com.benjaminfaal.ps4remotecontrol.ddp.model.Console;
import com.benjaminfaal.ps4remotecontrol.ddp.model.Status;
import com.benjaminfaal.ps4remotepkginstaller.Settings;
import com.benjaminfaal.ps4remotepkginstaller.authentication.event.AuthenticatedEvent;
import com.benjaminfaal.ps4remotepkginstaller.authentication.event.DeAuthenticatedEvent;
import com.benjaminfaal.ps4remotepkginstaller.event.AuthenticatingStatusEvent;
import com.benjaminfaal.ps4remotepkginstaller.event.TaskUpdateEvent;
import com.benjaminfaal.ps4remotepkginstaller.model.ManualConsole;
import com.benjaminfaal.ps4remotepkginstaller.model.api.request.InstallManifestJSONUrlRequest;
import com.benjaminfaal.ps4remotepkginstaller.model.api.request.InstallPKGUrlRequest;
import com.benjaminfaal.ps4remotepkginstaller.model.api.request.InstallPackagesRequest;
import com.benjaminfaal.ps4remotepkginstaller.model.api.request.InstallRequest;
import com.benjaminfaal.ps4remotepkginstaller.model.api.response.InstallResponse;
import com.benjaminfaal.ps4remotepkginstaller.model.api.response.TaskProgress;
import com.benjaminfaal.ps4remotepkginstaller.service.AuthenticationService;
import com.benjaminfaal.ps4remotepkginstaller.service.ManualConsoleService;
import com.benjaminfaal.ps4remotepkginstaller.service.RemotePKGInstallerService;
import com.benjaminfaal.ps4remotepkginstaller.util.Utils;
import com.github.junrar.Junrar;
import lombok.extern.apachecommons.CommonsLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@CommonsLog
@Component
public class MainUI extends JFrame {

    private static final int DISCOVER_TIMEOUT = 5000;

    // Swing
    private JTable tblTasks;

    private JButton btnInstallPKGs;

    private JButton btnInstallManifestJSON;

    private JButton btnInstallPKGUrl;

    private JComboBox<Console> cmbDiscoveredConsoles;

    private JButton btnDiscoverConsoles;

    private JButton btnAuthenticate;

    private JPanel contentPane;

    private JProgressBar selectedTaskProgressbar;

    private JTextArea selectedTaskDescription;

    private JPanel selectedTaskPanel;

    private JButton btnInstallRAR;

    private JCheckBox chkRemoteControl;

    private JButton btnAddConsole;

    private JButton btnRemoveConsole;

    private JButton btnEditConsole;

    // Spring
    @Value("${project.version}")
    private String projectVersion;

    @Value("${project.name}")
    private String projectName;

    @Autowired
    private RemotePKGInstallerService remotePKGInstallerService;

    @Autowired
    private Settings settings;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ManualConsoleService manualConsoleService;

    @Autowired
    private ApplicationContext applicationContext;

    private PS4CompanionAppConnection connection;

    public void init() {
        setContentPane(contentPane);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setTitle(projectName + " " + projectVersion);
        try {
            setIconImage(ImageIO.read(new ClassPathResource("icon.png").getInputStream()));
        } catch (IOException e) {
            log.error("Error setting icon: ", e);
        }

        initDiscoverUI();
        initTasksTable();
        initInstallPKGsButton();
        initInstallPKGsFromRARButton();
        initInstallManifestJSONUrlButton();
        initInstallPKGUrlButton();
    }

    private void initDiscoverUI() {
        cmbDiscoveredConsoles.setModel(new DefaultComboBoxModel<>());
        cmbDiscoveredConsoles.setRenderer(new DefaultListCellRenderer() {
            @Override
            public JComponent getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JComponent component = (JComponent) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                Console console = (Console) value;
                if (console != null) {
                    setText(console.getUserFriendlyName());
                }
                return component;
            }
        });
        cmbDiscoveredConsoles.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                Console console = (Console) event.getItem();
                handleConnect(console);
            }
        });

        btnDiscoverConsoles.addActionListener(e -> discoverConsoles());

        btnAuthenticate.addActionListener(e -> {
            Console console = (Console) cmbDiscoveredConsoles.getSelectedItem();
            if (authenticationService.isAuthenticated(console)) {
                authenticationService.deAuthenticate(console);
            } else {
                authenticationService.authenticate(console);
                connect(console);
            }
        });

        chkRemoteControl.setSelected(Boolean.parseBoolean(settings.getProperty("remoteControl", String.valueOf(true))));
        btnAuthenticate.setVisible(isRemoteControl());
        chkRemoteControl.addItemListener(e -> {
            boolean remoteControl = chkRemoteControl.isSelected();
            settings.setProperty("remoteControl", String.valueOf(remoteControl));
            btnAuthenticate.setVisible(remoteControl);

            if (remoteControl && cmbDiscoveredConsoles.getSelectedItem() != null) {
                handleConnect((Console) cmbDiscoveredConsoles.getSelectedItem());
            } else if (!remoteControl && connection != null) {
                try {
                    connection.disconnect();
                } catch (IOException ignored) {
                } finally {
                    connection = null;
                }
            }
        });

        DefaultComboBoxModel<Console> model = (DefaultComboBoxModel<Console>) cmbDiscoveredConsoles.getModel();
        btnAddConsole.addActionListener(e -> {
            String host = JOptionPane.showInputDialog(this, "Enter PS4 host", "Add PS4", JOptionPane.QUESTION_MESSAGE);
            if (StringUtils.hasText(host)) {
                validateHost(host);
                ManualConsole addedManualConsole = manualConsoleService.add(host);
                model.insertElementAt(addedManualConsole, 0);
                model.setSelectedItem(addedManualConsole);
            }
        });

        btnEditConsole.addActionListener(e -> {
            ManualConsole selectedManualConsole = (ManualConsole) cmbDiscoveredConsoles.getSelectedItem();
            String newHost = (String) JOptionPane.showInputDialog(this, "Enter PS4 host", "Add PS4", JOptionPane.QUESTION_MESSAGE, null, null, selectedManualConsole.getHost());
            if (StringUtils.hasText(newHost)) {
                validateHost(newHost);
                manualConsoleService.edit(selectedManualConsole, newHost);
                model.setSelectedItem(null);
                model.setSelectedItem(selectedManualConsole);
            }
        });

        btnRemoveConsole.addActionListener(e -> {
            ManualConsole selectedManualConsole = (ManualConsole) cmbDiscoveredConsoles.getSelectedItem();
            manualConsoleService.remove(selectedManualConsole);
            authenticationService.deAuthenticate(selectedManualConsole);
            model.removeElement(selectedManualConsole);
        });

        SwingUtilities.invokeLater(this::discoverConsoles);
    }

    private void handleConnect(Console console) {
        btnAuthenticate.setText(authenticationService.isAuthenticated(console) ? "Deauthenticate" : "Authenticate");
        btnAuthenticate.setEnabled(true);
        remotePKGInstallerService.setHost(console.getHost());

        boolean isManualConsole = console instanceof ManualConsole;
        btnEditConsole.setEnabled(isManualConsole);
        btnRemoveConsole.setEnabled(isManualConsole);

        new SwingWorker<Console, Void>() {
            @Override
            protected Console doInBackground() throws Exception {
                return PS4DDP.discover(console.getHost(), DISCOVER_TIMEOUT / 2);
            }

            @Override
            protected void done() {
                try {
                    Console discoveredConsole = get();
                    console.setStatus(discoveredConsole.getStatus());
                    discoveredConsole.getData().forEach(console::putIfAbsent);
                } catch (Exception e) {
                    console.setStatus(Status.UNKNOWN);
                    String message = "Error connecting to " + console.getUserFriendlyName() + System.lineSeparator() + e.getMessage();
                    log.error(message, e);
                } finally {
                    cmbDiscoveredConsoles.repaint();

                    refreshTasks();
                    if (isRemoteControl()) {
                        connect(console);
                    }
                }
            }
        }.execute();
    }

    private void validateHost(String host) {
        try {
            InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            JOptionPane.showMessageDialog(this, "Invalid host: " + host + System.lineSeparator() + e.getMessage(), "Invalid host", JOptionPane.ERROR_MESSAGE);
            throw new IllegalArgumentException("Invalid host: " + host, e);
        }
    }

    private void discoverConsoles() {
        DefaultComboBoxModel<Console> model = (DefaultComboBoxModel<Console>) cmbDiscoveredConsoles.getModel();
        model.removeAllElements();

        btnDiscoverConsoles.setText("Discovering...");
        btnDiscoverConsoles.setEnabled(false);
        btnAuthenticate.setEnabled(false);

        new SwingWorker<List<Console>, Console>() {
            @Override
            protected List<Console> doInBackground() throws Exception {
                publish(manualConsoleService.get().toArray(new ManualConsole[0]));
                return PS4DDP.discover(DISCOVER_TIMEOUT, this::publish);
            }

            @Override
            protected void process(List<Console> consoles) {
                for (Console console : consoles) {
                    model.addElement(console);
                }
            }

            @Override
            protected void done() {
                btnDiscoverConsoles.setEnabled(true);
                btnDiscoverConsoles.setText("Discover");
            }
        }.execute();
    }

    @EventListener
    public void handleAuthenticatedEvent(AuthenticatedEvent event) {
        if (event.getConsole() == cmbDiscoveredConsoles.getSelectedItem()) {
            btnAuthenticate.setText("Deauthenticate");
        }
    }

    @EventListener
    public void handleDeAuthenticatedEvent(DeAuthenticatedEvent event) {
        if (event.getConsole() == cmbDiscoveredConsoles.getSelectedItem()) {
            btnAuthenticate.setText("Authenticate");
        }
    }

    @EventListener
    public void handleAuthenticationStatusEvent(AuthenticatingStatusEvent event) {
        if (event.isAuthenticating()) {
            btnAuthenticate.setText("Authenticating...");
        } else {
            Console selectedConsole = (Console) cmbDiscoveredConsoles.getSelectedItem();
            if (selectedConsole != null) {
                btnAuthenticate.setText(authenticationService.isAuthenticated(selectedConsole)? "Deauthenticate" : "Authenticate");
            } else {
                btnAuthenticate.setText("Authenticate");
            }
        }
    }

    private void initTasksTable() {
        selectedTaskPanel.setVisible(false);
        tblTasks.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            List<Integer> selectedTaskIds = getSelectedTaskIds();
            selectedTaskPanel.setVisible(!selectedTaskIds.isEmpty());
            selectedTaskIds.stream().findFirst().ifPresent(this::updateSelectedTask);
        });

        DefaultTableModel model = new DefaultTableModel() {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        model.addColumn("ID");
        model.addColumn("Name");
        model.addColumn("Progress");
        model.addColumn("Time left");
        model.addColumn("Status");
        tblTasks.setModel(model);
        tblTasks.getTableHeader().setReorderingAllowed(false);

        tblTasks.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public JComponent getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JComponent component = (JComponent) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                component.setToolTipText(String.valueOf(value));
                return component;
            }
        });

        TableColumn idColumn = tblTasks.getColumn("ID");
        idColumn.setPreferredWidth(50);
        idColumn.setMaxWidth(50);

        TableColumn progressColumn = tblTasks.getColumn("Progress");
        progressColumn.setPreferredWidth(80);
        progressColumn.setMaxWidth(80);
        progressColumn.setCellRenderer((table, value, isSelected, hasFocus, row, column) -> {
            JProgressBar progressBar = new JProgressBar();
            if (value != null) {
                progressBar.setValue((Integer) value);
                progressBar.setString(value + "%");
                progressBar.setStringPainted(true);
            }
            return progressBar;
        });

        TableColumn timeLeftColumn = tblTasks.getColumn("Time left");
        timeLeftColumn.setPreferredWidth(80);
        timeLeftColumn.setMaxWidth(80);

        TableColumn statusColumn = tblTasks.getColumn("Status");
        statusColumn.setPreferredWidth(200);
        statusColumn.setMaxWidth(200);

        initTasksTablePopupMenu();

        Timer refreshTasksTimer = new Timer(5000, e -> {
            if (isWindowActive() && remotePKGInstallerService.countTasks() > 0 && isRemotePKGInstallerRunning()) {
                refreshTasks();
            }
        });
        refreshTasksTimer.setInitialDelay(0);
        refreshTasksTimer.start();
    }

    private void updateSelectedTask(Integer taskId) {
        TaskProgress task = remotePKGInstallerService.getCachedTask(taskId);
        TitledBorder border = (TitledBorder) selectedTaskPanel.getBorder();
        border.setTitle("Task " + task.getId());

        int percentage = calculateTaskPercentage(task);
        selectedTaskProgressbar.setValue(percentage);
        selectedTaskProgressbar.setString(percentage + "%");
        selectedTaskProgressbar.setStringPainted(true);

        String installRequestToString = installRequestToString(remotePKGInstallerService.getInstallRequest(task.getId()));
        installRequestToString += System.lineSeparator() + "Time left: " + formatTimeLeft(task);
        installRequestToString += System.lineSeparator() + "Status: " + getStatus(task);
        selectedTaskDescription.setText(installRequestToString);
    }

    private List<Integer> getSelectedTaskIds() {
        return Arrays.stream(tblTasks.getSelectedRows())
                .mapToObj(row -> ((Integer) tblTasks.getValueAt(row, 0)))
                .collect(Collectors.toList());
    }

    private void initTasksTablePopupMenu() {
        JPopupMenu taskPopupMenu = new JPopupMenu("Task");

        JMenuItem refreshMenuItem = new JMenuItem("Refresh");
        refreshMenuItem.addActionListener(e -> {
            if (checkRemotePKGInstallerIsRunning()) {
                getSelectedTaskIds().forEach(this::refreshTask);
            }
        });
        taskPopupMenu.add(refreshMenuItem);

        taskPopupMenu.addSeparator();

        JMenuItem pauseMenuItem = new JMenuItem("Pause");
        pauseMenuItem.addActionListener(e -> {
            if (checkRemotePKGInstallerIsRunning()) {
                getSelectedTaskIds().forEach(remotePKGInstallerService::pauseTask);
            }
        });
        taskPopupMenu.add(pauseMenuItem);

        JMenuItem resumeMenuItem = new JMenuItem("Resume");
        resumeMenuItem.addActionListener(e -> {
            if (checkRemotePKGInstallerIsRunning()) {
                getSelectedTaskIds().forEach(remotePKGInstallerService::resumeTask);
            }
        });
        taskPopupMenu.add(resumeMenuItem);

        JMenuItem stopMenuItem = new JMenuItem("Stop");
        stopMenuItem.addActionListener(e -> {
            if (checkRemotePKGInstallerIsRunning()) {
                getSelectedTaskIds().forEach(remotePKGInstallerService::stopTask);
            }
        });
        taskPopupMenu.add(stopMenuItem);

        taskPopupMenu.addSeparator();

        JMenuItem removeMenuItem = new JMenuItem("Remove");
        removeMenuItem.addActionListener(e -> {
            if (checkRemotePKGInstallerIsRunning()) {
                getSelectedTaskIds().forEach(remotePKGInstallerService::removeTask);
                refreshTasks();
            }
        });
        taskPopupMenu.add(removeMenuItem);

        taskPopupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                int taskIndex = tblTasks.rowAtPoint(tblTasks.getMousePosition());
                if (taskIndex != -1) {
                    if (Arrays.stream(tblTasks.getSelectedRows()).noneMatch(value -> value == taskIndex)) {
                        tblTasks.setRowSelectionInterval(taskIndex, taskIndex);
                    } else if (tblTasks.getSelectedRows().length == 0) {
                        tblTasks.setRowSelectionInterval(taskIndex, taskIndex);
                    }
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        tblTasks.setComponentPopupMenu(taskPopupMenu);
    }

    @EventListener
    public void onTaskUpdateEvent(TaskUpdateEvent event) {
        if (isWindowActive()) {
            new SwingWorker<Boolean, Void>() {
                @Override
                protected Boolean doInBackground() throws Exception {
                    return isRemotePKGInstallerRunning();
                }

                @Override
                protected void done() {
                    try {
                        if (get()) {
                            refreshTask(event.getTaskId());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }.execute();
        }
    }

    private void refreshTasks() {
        new SwingWorker<List<TaskProgress>, Void>() {
            @Override
            protected List<TaskProgress> doInBackground() throws Exception {
                if (!isRemotePKGInstallerRunning()) {
                    throw new IllegalStateException("Remote PKG Installer is not running");
                }
                return remotePKGInstallerService.getTasks();
            }

            @Override
            protected void done() {
                try {
                    List<TaskProgress> tasks = get();
                    DefaultTableModel model = (DefaultTableModel) tblTasks.getModel();
                    model.setRowCount(tasks.size());
                    for (TaskProgress task : tasks) {
                        updateTask(task, model, tasks.indexOf(task));
                    }
                } catch (Exception e) {
                    log.error("Error refreshing tasks: ", e);
                }
            }

        }.execute();
    }

    private void refreshTask(Integer taskId) {
        if (!remotePKGInstallerService.getTaskIds().contains(taskId)) {
            return;
        }
        new SwingWorker<TaskProgress, Void>() {
            @Override
            protected TaskProgress doInBackground() throws Exception {
                return remotePKGInstallerService.getTask(taskId);
            }

            @Override
            protected void done() {
                try {
                    TaskProgress task = get();
                    DefaultTableModel model = (DefaultTableModel) tblTasks.getModel();

                    int index = remotePKGInstallerService.getTaskIds().indexOf(taskId);
                    if (index != -1) {
                        model.setRowCount(remotePKGInstallerService.countTasks());
                        updateTask(task, model, index);
                    }
                } catch (Exception e) {
                    log.error("Error refreshing task " + taskId, e);
                }
            }
        }.execute();
    }

    private void updateTask(TaskProgress task, DefaultTableModel model, int index) {
        model.setValueAt(task.getId(), index, 0);
        model.setValueAt(installRequestToString(remotePKGInstallerService.getInstallRequest(task.getId())), index, 1);
        model.setValueAt(calculateTaskPercentage(task), index, 2);
        model.setValueAt(formatTimeLeft(task), index, 3);
        model.setValueAt(getStatus(task), index, 4);

        if (getSelectedTaskIds().contains(task.getId())) {
            updateSelectedTask(task.getId());
        }
    }

    private String formatTimeLeft(TaskProgress task) {
        if (calculateTaskPercentage(task) == 100) {
            return "∞";
        }
        String timeLeft = "";
        if (task.getRestSecTotal() != null) {
            if (task.getRestSecTotal() == 0) {
                timeLeft = "∞";
            } else {
                timeLeft = LocalTime.MIDNIGHT.plus(Duration.ofSeconds(task.getRestSecTotal())).format(DateTimeFormatter.ISO_LOCAL_TIME);
            }
        }
        return timeLeft;
    }

    private int calculateTaskPercentage(TaskProgress task) {
        if (task.getTransferredTotal() != null && task.getLengthTotal() != null) {
            Long lengthTotal = Long.decode(task.getLengthTotal());
            Long transferredTotal = Long.decode(task.getTransferredTotal());
            if (lengthTotal > 0) {
                return (int) ((transferredTotal * 100) / lengthTotal);
            }
        }
        return 0;
    }

    private String getStatus(TaskProgress task) {
        String bits = task.getBits();
        if (task.getErrorCode() != null) {
            return taskErrorCodeToString(task.getErrorCode());
        } else if (!Objects.equals(task.getError(), 0)) {
            return taskErrorToString(task.getError());
        } else if (bits == null) {
            return null;
        }

        Integer statusBits = Integer.decode(bits);
        return Arrays.stream(TaskProgress.Status.values())
                .filter(status -> (statusBits & status.getBits()) == status.getBits())
                .findFirst()
                .map(TaskProgress.Status::getDescription)
                .orElse("Unknown " + bits);
    }

    private String taskErrorCodeToString(String errorCode) {
        switch (errorCode) {
            case "0x80990015":
                return "PKG already downloading or installed, check PS4.";
        }
        PS4ErrorCode ps4ErrorCode = PS4ErrorCodes.findByReturnCode(errorCode);
        if (ps4ErrorCode != null) {
            return ps4ErrorCode.toString();
        }
        return errorCode;
    }

    private String taskErrorToString(Integer error) {
        PS4ErrorCode ps4ErrorCode = PS4ErrorCodes.findBySigned(error);
        if (ps4ErrorCode != null) {
            return ps4ErrorCode.toString();
        }
        return String.valueOf(error);
    }

    private String installRequestToString(InstallRequest installRequest) {
        if (installRequest instanceof InstallPackagesRequest) {
            return String.join(", ", ((InstallPackagesRequest) installRequest).getLocalFiles());
        } else if (installRequest instanceof InstallPKGUrlRequest) {
            return String.join(", ", ((InstallPKGUrlRequest) installRequest).getPackages());
        } else if (installRequest instanceof InstallManifestJSONUrlRequest) {
            return ((InstallManifestJSONUrlRequest) installRequest).getUrl();
        }
        throw new IllegalArgumentException("Unknown InstallRequest type: " + installRequest.getClass());
    }
    
    private void wakeUp(Console console) {
        try {
            PS4DDP.wakeUp(console.getHost(), authenticationService.authenticate(console));
        } catch (IOException e) {
            log.error("Error waking up PS4: ", e);
            JOptionPane.showMessageDialog(this, "Error waking up PS4: " + e.getMessage(), "Error waking up PS4", JOptionPane.ERROR_MESSAGE);
        }
    }

    {
        new Timer(10000, event -> {
            try {
                if (isRemoteControl() && connection != null && connection.isConnected()) {
                    connection.sendStatus(0);
                }
            } catch (IOException e) {
                log.error("Lost connection to PS4: ", e);
            }
        }).start();
    }

    private void connect(Console console) {
        if (!isRemoteControl()) {
            throw new IllegalStateException("Remote control is disabled");
        }
        authenticationService.authenticate(console);
        if (authenticationService.isAuthenticated(console)) {
            if (console.getStatus() == Status.STANDBY) {
                String message = "PS4 is standby, do you want to send a wakeup request?";
                if (JOptionPane.showConfirmDialog(this, message, "PS4 is standby", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    wakeUp(console);
                }
            } else {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Exception ignored) {
                    }
                }

                String credential = authenticationService.authenticate(console);
                try {
                    connection = new PS4CompanionAppConnection(console.getHost(), credential);
                    PS4DDP.launch(console.getHost(), credential);
                    connection.connect();
                    login(console, connection);
                } catch (Exception e) {
                    connection = null;
                    String message = "Error connecting to PS4: " + console.getUserFriendlyName();
                    log.error(message, e);
                    JOptionPane.showMessageDialog(this, message, message, JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void login(Console console, PS4CompanionAppConnection connection) {
        String pincode = "";
        String passcode = "";
        try {
            LoginResponse loginResponse = connection.login(pincode, passcode, projectName, projectName);
            while (!loginResponse.isSuccess()) {
                String message = getLoginStatusMessage(loginResponse.getStatus());
                switch (loginResponse.getStatus()) {
                    case INVALID_PIN_FORMAT:
                    case PIN_NOT_SPECIFIED:
                    case INVALID_PIN:
                        pincode = JOptionPane.showInputDialog(this, message, pincode).replace(" ", "");
                        break;
                    case INVALID_PASSCODE:
                    case PASSCODE_NOT_SPECIFIED:
                        passcode = JOptionPane.showInputDialog(this, message, pincode);
                        break;
                    case INVALID_CREDENTIAL:
                        authenticationService.deAuthenticate(console);
                        throw new IllegalStateException(message);
                    default:
                        JOptionPane.showMessageDialog(this, message);
                        break;
                }
                loginResponse = connection.login(pincode, passcode, projectName, projectName);
            }
        } catch (Exception e) {
            String message = "Error logging in to PS4: " + console.getUserFriendlyName();
            log.error(message, e);
            JOptionPane.showMessageDialog(this, message + System.lineSeparator() + e.getMessage(), message, JOptionPane.ERROR_MESSAGE);
        }
    }

    private String getLoginStatusMessage(LoginResponse.Status status) {
        switch (status) {
            case PIN_NOT_SPECIFIED:
            case INVALID_PIN:
                return "Invalid pin, please enter a valid pin from the PS4 Mobile app connection settings.";
            case INVALID_PIN_FORMAT:
                return "Invalid pin format, please enter a valid pin from the PS4 Mobile app connection settings.";
            case INVALID_PASSCODE:
            case PASSCODE_NOT_SPECIFIED:
                return "Invalid passcode, please enter a valid passcode of the PS4.";
            case INVALID_CREDENTIAL:
                return "Invalid credential, please authenticate again.";
            case LOCKED:
                return "PS4 is locked, please unlock before trying again.";
        }
        return "Unknown login error, please try again";
    }

    private boolean isServerEnabled() {
        return applicationContext instanceof WebApplicationContext;
    }

    private void initInstallPKGsButton() {
        btnInstallPKGs.setEnabled(isServerEnabled());
        btnInstallPKGs.addActionListener(e -> {
            if (!checkRemotePKGInstallerIsRunning()) {
                return;
            }
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileFilter(new FileNameExtensionFilter("PS4 PKG files", "pkg"));
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setMultiSelectionEnabled(true);
            if (settings.containsKey("lastDirectory")) {
                fileChooser.setCurrentDirectory(new File(settings.getProperty("lastDirectory")));
            }
            if (fileChooser.showOpenDialog(rootPane) == JFileChooser.APPROVE_OPTION) {
                File[] selectedFiles = fileChooser.getSelectedFiles();
                if (selectedFiles.length > 0) {
                    for (File selectedFile : selectedFiles) {
                        settings.setProperty("lastDirectory", selectedFile.getParent());
                    }
                    doInstallInBackground(() -> remotePKGInstallerService.installFiles(selectedFiles));
                }
            }
        });
    }

    private void initInstallPKGsFromRARButton() {
        btnInstallRAR.setEnabled(isServerEnabled());
        btnInstallRAR.addActionListener(event -> {
            if (!checkRemotePKGInstallerIsRunning()) {
                return;
            }
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileFilter(new FileNameExtensionFilter("RAR files", "rar"));
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fileChooser.setMultiSelectionEnabled(false);
            if (settings.containsKey("lastDirectory")) {
                fileChooser.setCurrentDirectory(new File(settings.getProperty("lastDirectory")));
            }
            if (fileChooser.showOpenDialog(rootPane) == JFileChooser.APPROVE_OPTION) {
                File rarFile = fileChooser.getSelectedFile();
                if (rarFile != null) {
                    settings.setProperty("lastDirectory", rarFile.getParent());

                    installRAR(rarFile);
                }
            }
        });
    }

    private void installRAR(File rarFile) {
        List<File> extracted;
        try {
            if (Junrar.getContentsDescription(rarFile).stream().noneMatch(contentDescription -> contentDescription.path.endsWith(".pkg"))) {
                JOptionPane.showMessageDialog(this, rarFile + " does not contain any PKG files", "No PKG files", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Path destinationFolder = Utils.getWorkingDirectory().resolve("rar").resolve(rarFile.getName());
            if (!Files.exists(destinationFolder)) {
                Files.createDirectories(destinationFolder);
            }
            Path extractedLockFile = destinationFolder.resolve(".extracted");
            if (!Files.exists(extractedLockFile)) {
                JOptionPane.showMessageDialog(this, "Extracting " + rarFile + " to " + destinationFolder);
                extracted = Junrar.extract(rarFile, destinationFolder.toFile());
                Files.createFile(extractedLockFile);
            } else {
                extracted = Files.list(destinationFolder).map(Path::toFile).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("Error extracting " + rarFile, e);
            JOptionPane.showMessageDialog(this, "Error extracting " + rarFile + System.lineSeparator() + e.getMessage(), "Error extracting " + rarFile, JOptionPane.ERROR_MESSAGE);
            return;
        }
        File[] extractedPKGFiles = extracted.stream()
                .filter(file -> new FileNameExtensionFilter("PS4 PKG files", "pkg").accept(file))
                .toArray(File[]::new);
        if (extractedPKGFiles.length == 0) {
            JOptionPane.showMessageDialog(this, "No PKG files found inside " + rarFile);
        } else {
            doInstallInBackground(() -> remotePKGInstallerService.installFiles(extractedPKGFiles));
        }
    }

    private void initInstallPKGUrlButton() {
        btnInstallPKGUrl.addActionListener(e -> {
            if (!checkRemotePKGInstallerIsRunning()) {
                return;
            }
            String pkgUrl = JOptionPane.showInputDialog("Enter PKG URL");
            if (StringUtils.hasText(pkgUrl)) {
                doInstallInBackground(() -> remotePKGInstallerService.installPKGUrl(pkgUrl));
            }
        });
    }

    private void initInstallManifestJSONUrlButton() {
        btnInstallManifestJSON.addActionListener(e -> {
            if (!checkRemotePKGInstallerIsRunning()) {
                return;
            }
            String manifestJSONUrl = JOptionPane.showInputDialog("Enter manifest JSON URL");
            if (StringUtils.hasText(manifestJSONUrl)) {
                doInstallInBackground(() -> remotePKGInstallerService.installManifestJSONUrl(manifestJSONUrl));
            }
        });
    }

    private void doInstallInBackground(Supplier<InstallResponse> installResponseSupplier) {
        if (!checkRemotePKGInstallerIsRunning()) {
            return;
        }
        new SwingWorker<InstallResponse, Void>() {
            @Override
            protected InstallResponse doInBackground() throws Exception {
                return installResponseSupplier.get();
            }

            @Override
            protected void done() {
                try {
                    InstallResponse response = get();
                    if (!response.isSuccess()) {
                        String message = "Error installing: " + System.lineSeparator();
                        if (response.getError() != null) {
                            message += response.getError();
                        } else if (response.getErrorCode() != null) {
                            message += taskErrorCodeToString(response.getErrorCode()) + " (" + response.getErrorCode() + ")";
                        }
                        log.error(message);
                        JOptionPane.showMessageDialog(MainUI.this, message, "Error installing", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    log.error("Error installing: ", e);
                    String message = "Error installing: " + System.lineSeparator() + e.getMessage();
                    JOptionPane.showMessageDialog(MainUI.this, message, "Error installing", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private boolean checkRemotePKGInstallerIsRunning() {
        if (isRemotePKGInstallerRunning()) {
            return true;
        } else if (isRemoteControl()) {
            String message = "Remote PKG Installer is not running in the foreground. Do you want to start Remote PKG Installer?";
            if (JOptionPane.showConfirmDialog(this, message, "Start Remote PKG Installer", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                try {
                    boolean started = startRemotePKGInstaller();
                    if (!started) {
                        throw new IllegalStateException("Failed to start Remote PKG Installer, please check the PS4");
                    }
                    return true;
                } catch (Exception e) {
                    String errorMessage = "Error starting Remote PKG Installer please check the PS4";
                    log.error(errorMessage, e);
                    JOptionPane.showMessageDialog(this, errorMessage + System.lineSeparator() + e.getMessage(), "Error starting Remote PKG Installer", JOptionPane.ERROR_MESSAGE);
                }
            }
            return false;
        } else {
            //JOptionPane.showMessageDialog(this, "Remote PKG Installer is not running in the foreground. Please start it on the PS4.", "Start Remote PKG Installer", JOptionPane.INFORMATION_MESSAGE);
            return true;
        }
    }

    private boolean startRemotePKGInstaller() throws IOException, TimeoutException {
        if (connection != null && connection.startTitle("FLTZ00003").isSuccess()) {
            long started = System.currentTimeMillis();
            while (System.currentTimeMillis() - started < 3000 && !isRemotePKGInstallerRunning()) {
            }
            boolean running = isRemotePKGInstallerRunning();
            if (!running) {
                throw new TimeoutException("Failed to start Remote PKG Installer within 3 seconds");
            }
            return running;
        }
        return false;
    }

    private boolean isRemotePKGInstallerRunning() {
        if (cmbDiscoveredConsoles.getSelectedItem() == null) {
            return false;
        }
        try {
            Console selectedConsole = (Console) cmbDiscoveredConsoles.getSelectedItem();
            Console console = PS4DDP.discover(selectedConsole.getHost(), 1000);
            return Objects.equals(console.get("running-app-titleid"), "FLTZ00003")
                    &&
                    Objects.equals(console.get("running-app-name"), "Remote PKG installer");
        } catch (Exception e) {
            log.error("Error checking whether Remote PKG Installer is running by DDP", e);
        }
        try {
            return remotePKGInstallerService.isRunning();
        } catch (Exception e) {
            log.error("Error checking whether Remote PKG Installer is running by REST", e);
        }
        return false;
    }

    private boolean isWindowActive() {
        return getState() != Frame.ICONIFIED;
    }

    private boolean isRemoteControl() {
        return chkRemoteControl.isSelected();
    }

}
