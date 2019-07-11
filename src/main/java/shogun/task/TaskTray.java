package shogun.task;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import shogun.logging.LoggerFactory;
import shogun.sdk.SDK;
import shogun.sdk.SDKLauncher;
import shogun.sdk.Version;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static javax.swing.JOptionPane.QUESTION_MESSAGE;

public class TaskTray {
    private final static Logger logger = LoggerFactory.getLogger();
    private ResourceBundle bundle = ResourceBundle.getBundle("message", Locale.getDefault());

    private SDK sdk = new SDK();
    private SystemTray tray;
    private TrayIcon icon;
    private PopupMenu popup = new PopupMenu();
    private final Frame thisFrameMakesDialogsAlwaysOnTop = new Frame();

    private DukeThread duke = new DukeThread();

    class DukeThread extends Thread {
        private boolean dukeRolling = false;
        List<Image> animatedDuke;

        DukeThread() {
            setName("Duke roller");
            setDaemon(true);
            logger.debug("Loading images.");
            animatedDuke = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Image image = Toolkit.getDefaultToolkit().createImage(ClassLoader.getSystemResource("images/duke-64x64-anim" + i + ".png"));
                animatedDuke.add(image);
            }

        }

        private void startRoll() {
            dukeRolling = true;
            this.interrupt();
        }

        private void stopRoll() {
            dukeRolling = false;
            this.interrupt();
        }

        public void run() {
            //noinspection InfiniteLoopStatement
            while (true) {
                while (dukeRolling) {
                    for (Image animation : animatedDuke) {
                        EventQueue.invokeLater(() -> icon.setImage(animation));
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        if (!dukeRolling) {
                            break;
                        }
                    }
                }
                EventQueue.invokeLater(() -> icon.setImage(animatedDuke.get(0)));
                try {
                    synchronized (this) {
                        wait();
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private ImageIcon dialogIcon = new ImageIcon(Toolkit.getDefaultToolkit().createImage(ClassLoader.getSystemResource("images/duke-128x128.png")));

    private final ExecutorService executorService = Executors.newFixedThreadPool(1,
            new ThreadFactory() {
                int count = 0;

                @Override
                public Thread newThread(@NotNull Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName(String.format("Shogun Executor[%d]", count++));
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );


    public void show() {
        thisFrameMakesDialogsAlwaysOnTop.setAlwaysOnTop(true);
        duke.start();

        EventQueue.invokeLater(() -> {
            logger.debug("Preparing task tray.");
            tray = SystemTray.getSystemTray();
            icon = new TrayIcon(duke.animatedDuke.get(0), "Shogun", popup);
            icon.setImageAutoSize(true);
            try {
                tray.add(icon);
            } catch (AWTException e) {
                quit();
            }
        });
        executorService.execute(this::initializeMenuItems);


    }

    private void quit() {
        EventQueue.invokeLater(() -> tray.remove(icon));
        System.exit(0);
    }


    private synchronized void initializeMenuItems() {
        logger.debug("Initializing menu items.");
        duke.startRoll();
        Menu candidatesMenu = new Menu(bundle.getString("otherCandidates"));
        EventQueue.invokeLater(() -> popup.add(candidatesMenu));

        if (!sdk.isInstalled()) {
            logger.debug("SDKMAN! not installed.");
            MenuItem installMenu = new MenuItem(bundle.getString("installSDKMan"));
            installMenu.addActionListener(e -> installSDKMAN());
            EventQueue.invokeLater(() -> popup.add(installMenu));
        }

        if (sdk.isInstalled()) {
            String version = sdk.getVersion();
            logger.debug("SDKMAN! version {} detected.", version);
            MenuItem versionLabel = new MenuItem(version + (sdk.isOffline() ? " (offline)" : ""));
            versionLabel.addActionListener(e -> initializeMenuItems());
            EventQueue.invokeLater(() -> popup.add(versionLabel));
        }
        MenuItem quitMenu = new MenuItem(bundle.getString("quit"));
        quitMenu.addActionListener(e -> quit());
        EventQueue.invokeLater(() -> popup.add(quitMenu));

        List<String> installedCandidates = new ArrayList<>();
        if (sdk.isInstalled()) {
            sdk.getInstalledCandidates()
                    .forEach(e -> {
                        logger.debug("Installed candidate: {}", e);
                        installedCandidates.add(e);
                        new Candidate(e, sdk.list(e));
                    });
        }
        if (!sdk.isOffline()) {
            logger.debug("Offline mode.");
            // list available candidates
            sdk.listCandidates().stream()
                    .filter(e -> !installedCandidates.contains(e))
                    .forEach(e -> {
                        logger.debug("Available candidate: {}", e);
                        new Candidate(e, sdk.list(e));
                    });
        }
        duke.stopRoll();
    }

    class Candidate {
        private final String candidate;
        private List<Version> versions = new ArrayList<>();
        Menu candidateMenu;

        Candidate(String candidate, List<Version> versions) {
            this.candidate = candidate;
            versions.stream().filter(e -> e.isInstalled() || e.isLocallyInstalled()).forEach(e -> this.versions.add(e));
            versions.stream().filter(e -> !e.isInstalled() && !e.isLocallyInstalled()).forEach(e -> this.versions.add(e));
            logger.debug("Building menu for : {}", candidate);
            candidateMenu = new Menu(candidate);
            if (isInstalled()) {
                addToInstalledCandidatesMenu(candidateMenu);
            } else {
                addToAvailableCandidatesMenu(candidateMenu);
            }
            refreshMenus();
        }

        void refreshMenus() {
            EventQueue.invokeLater(() -> setRootMenuLabel(candidateMenu));
            EventQueue.invokeLater(candidateMenu::removeAll);
            for (Version jdk : versions) {
                Menu jdkMenuItem = new Menu(toLabel(jdk));
                updateMenu(jdkMenuItem, jdk);
                EventQueue.invokeLater(() -> candidateMenu.add(jdkMenuItem));
            }

        }

        private boolean isInstalled() {
            return versions.stream().anyMatch(e -> e.isInstalled() || e.isLocallyInstalled());
        }

        void setRootMenuLabel(Menu menu) {
            // add version string in use
            EventQueue.invokeLater(() ->
                    versions.stream()
                            .filter(Version::isUse)
                            .findFirst()
                            .ifPresentOrElse(e -> menu.setLabel(candidate + " > " + e.toString()),
                                    () -> menu.setLabel(candidate)));
        }

        Menu find(Menu menu, Version version) {
            for (int i = 0; i < menu.getItemCount(); i++) {
                Menu item = (Menu) menu.getItem(i);
                if (item.getLabel().contains(version.toString())) {
                    return item;
                }
            }
            throw new IllegalStateException("menu not found");
        }

        Menu getAvailableCandidatesMenu() {
            return (Menu) popup.getItem(popup.getItemCount() - 3);
        }

        void setDefault(Version version) {
            executorService.execute(() -> {
                logger.debug("Set default: {}", version);
                duke.startRoll();
                sdk.makeDefault(version.getCandidate(), version);
                Menu menu = candidateMenu;
                Optional<Version> lastDefault = versions.stream().filter(Version::isUse).findFirst();
                lastDefault.ifPresent(oldDefaultVersion -> {
                    oldDefaultVersion.setUse(false);
                    EventQueue.invokeLater(() -> {
                        Menu oldDefaultMenu = find(menu, oldDefaultVersion);
                        updateMenu(oldDefaultMenu, oldDefaultVersion);
                    });
                });

                Version newDefaultVersion = versions.get(versions.indexOf(version));
                newDefaultVersion.setUse(true);
                Menu newDefaultMenu = find(menu, newDefaultVersion);
                EventQueue.invokeLater(() -> updateMenu(newDefaultMenu, newDefaultVersion));
                setRootMenuLabel(menu);
                duke.stopRoll();
            });
        }

        void install(Version version) {
            executorService.execute(() -> {
                logger.debug("Install: {}", version);
                int response = JOptionPane.showConfirmDialog(thisFrameMakesDialogsAlwaysOnTop,
                        getMessage("confirmInstallMessage", version.getCandidate(), version.toString()),
                        getMessage("confirmInstallTitle", version.getCandidate(), version.toString()), JOptionPane.OK_CANCEL_OPTION,
                        QUESTION_MESSAGE, dialogIcon);
                if (response == JOptionPane.OK_OPTION) {
                    duke.startRoll();
                    var wasInstalled = isInstalled();
                    sdk.install(version);
                    versions = sdk.list(candidate);
                    refreshMenus();
                    if (!wasInstalled) {
                        // this candidate wasn't installed. move to installed candidates list
                        Menu candidateRootMenu = candidateMenu;
                        EventQueue.invokeLater(() -> getAvailableCandidatesMenu().remove(candidateRootMenu));
                        addToInstalledCandidatesMenu(candidateRootMenu);
                    }
                    duke.stopRoll();
                }
            });
        }

        void uninstall(Version version) {
            executorService.execute(() -> {
                logger.debug("Uninstall: {}", version);
                int response = JOptionPane.showConfirmDialog(thisFrameMakesDialogsAlwaysOnTop,
                        getMessage("confirmUninstallMessage", version.getCandidate(), version.toString()),
                        getMessage("confirmUninstallTitle", version.getCandidate(), version.toString()), JOptionPane.OK_CANCEL_OPTION,
                        QUESTION_MESSAGE, dialogIcon);
                if (response == JOptionPane.OK_OPTION) {
                    duke.startRoll();
                    var wasInstalled = isInstalled();
                    sdk.uninstall(version);
                    versions = sdk.list(candidate);
                    refreshMenus();
                    if (wasInstalled && !isInstalled()) {
                        // no version of this candidate is installed anymore. move to available candidates list
                        Menu candidateRootMenu = candidateMenu;
                        EventQueue.invokeLater(() -> popup.remove(candidateRootMenu));
                        addToAvailableCandidatesMenu(candidateRootMenu);
                    }
                    duke.stopRoll();
                }
            });
        }

        void addToInstalledCandidatesMenu(Menu menu) {
            boolean added = false;
            for (int i = 0; i < popup.getItemCount() - 3; i++) {
                MenuItem item = popup.getItem(i);
                if (0 < item.getLabel().compareTo(candidate)) {
                    int index = i;
                    EventQueue.invokeLater(() -> popup.insert(menu, index));
                    added = true;
                    break;
                }
            }
            if (!added) {
                // last item in installed candidates items
                EventQueue.invokeLater(() -> popup.insert(menu, popup.getItemCount() - 3));
            }
        }

        void addToAvailableCandidatesMenu(Menu menu) {
            boolean added = false;
            Menu otherCandidate = getAvailableCandidatesMenu();
            for (int i = 0; i < otherCandidate.getItemCount(); i++) {
                Menu item = (Menu) otherCandidate.getItem(i);
                if (0 < item.getLabel().compareTo(candidate)) {
                    int index = i;
                    EventQueue.invokeLater(() -> otherCandidate.insert(menu, index));
                    added = true;
                    break;
                }
            }
            if (!added) {
                EventQueue.invokeLater(() -> otherCandidate.add(menu));
            }
        }

        private void updateMenu(Menu menu, Version jdk) {
            EventQueue.invokeLater(() -> {
                menu.setLabel(toLabel(jdk));
                menu.removeAll();
                if (jdk.isInstalled() || jdk.isLocallyInstalled()) {
                    if (!jdk.isUse()) {
                        MenuItem menuItem = new MenuItem(bundle.getString("makeDefault"));
                        menuItem.addActionListener(e -> setDefault(jdk));
                        menu.add(menuItem);
                    }

                    MenuItem openInTerminalMenu = new MenuItem(getMessage("openInTerminal", jdk.getIdentifier()));
                    openInTerminalMenu.addActionListener(e -> openInTerminal(jdk));
                    menu.add(openInTerminalMenu);

                    MenuItem copyPathMenu = new MenuItem(bundle.getString("copyPath"));
                    copyPathMenu.addActionListener(e -> copyPathToClipboard(jdk));
                    menu.add(copyPathMenu);

                    MenuItem revealInFinderMenu = new MenuItem(bundle.getString("revealInFinder"));
                    revealInFinderMenu.addActionListener(e -> revealInFinder(jdk));
                    menu.add(revealInFinderMenu);

                    MenuItem uninstallItem = new MenuItem(bundle.getString("uninstall"));
                    uninstallItem.addActionListener(e -> uninstall(jdk));
                    menu.add(uninstallItem);
                }

                if (!jdk.isInstalled() && !jdk.isLocallyInstalled()) {
                    MenuItem menuItem = new MenuItem(bundle.getString("install"));
                    menuItem.addActionListener(e -> install(jdk));
                    menu.add(menuItem);
                }
            });
        }
    }

    private void installSDKMAN() {
        executorService.execute(() -> {
            duke.startRoll();
            sdk.install();
            EventQueue.invokeLater(() -> popup.removeAll());
            initializeMenuItems();
            duke.stopRoll();
        });
    }

    private void openInTerminal(Version jdk) {
        SDKLauncher.exec("bash", "-c", String.format("osascript -e 'tell application \"Terminal\" to do script \"sdk use %s %s\"';osascript -e 'tell application \"Terminal\" to activate'", jdk.getCandidate(), jdk.getIdentifier()));
    }

    private void copyPathToClipboard(Version jdk) {
        Toolkit kit = Toolkit.getDefaultToolkit();
        Clipboard clip = kit.getSystemClipboard();

        StringSelection ss = new StringSelection(jdk.getPath());
        clip.setContents(ss, ss);
    }

    private void revealInFinder(Version jdk) {
        ProcessBuilder pb = new ProcessBuilder("open", jdk.getPath());
        try {
            Process process = pb.start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static String toLabel(Version version) {
        String label = version.isUse() ? ">" : "  ";
        label += version.toString();
        if (version.getStatus().length() != 0) {
            label += " (" + version.getStatus() + ")";
        }
        return label;
    }

    private String getMessage(String pattern, String... values) {
        MessageFormat formatter = new MessageFormat(bundle.getString(pattern));
        return formatter.format(values);
    }
}