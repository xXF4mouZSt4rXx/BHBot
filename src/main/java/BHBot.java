import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.util.*;

import com.google.common.base.Throwables;
import net.pushover.client.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.apache.logging.log4j.core.config.ConfigurationSource;


public class BHBot {

	private static final String PROGRAM_NAME = "BHBot";
	private static String BHBotVersion;

	private static Thread mainThread;
	static MainThread main;
	/** Set it to true to end main loop and end program gracefully */
	private static boolean finished = false;
	
	static Settings settings = new Settings().setDebug();
	static Scheduler scheduler = new Scheduler();

	static PushoverClient poClient = new PushoverRestClient();

	static String chromeDriverAddress = "127.0.0.1:9515";

	static String chromiumExePath = "C:\\Users\\"+System.getProperty("user.name")+"\\AppData\\Local\\Chromium\\Application\\chrome.exe";
	static String chromeDriverExePath = "./chromedriver.exe";
	private static String cuesPath = "./cues/";
	static String screenshotPath = "./screenshots/";

	/** log4j logger */
	static Logger logger;

	public static void main(String[] args) {
		
		// process launch arguments
		String file = Settings.DEFAULT_SETTINGS_FILE;

		// We make sure that our configurationFactory is added to the list of configuration factories.
		System.setProperty("log4j.configurationFactory", "BHBotConfigurationFactory");

		for (int i = 0; i < args.length; i++) { //select settings file to load
			switch (args[i]) {
				case "settings":
					file = args[i + 1];
					i++;
					break;
				case "init":  //start bot in idle mode
				case "idle":  //start bot in idle mode
					file = "LOAD_IDLE_SETTINGS";
					i++;
					break;
				case "chromium":
				case "chromiumpath":
					chromiumExePath = args[i + 1];
					break;
				case "chromedriver":
				case "chromedriverpath":
					chromeDriverExePath = args[i + 1];
					break;
				case "chromedriveraddress":  //change chrome driver port
					chromeDriverAddress = args[i + 1];
					i++;
					break;
			}
		}

		if ("LOAD_IDLE_SETTINGS".equals(file)){
			settings.setIdle();
		} else {
			settings.load(file);
		}

		logger = LogManager.getRootLogger();

		Properties properties = new Properties();
		try {
			properties.load(BHBot.class.getResourceAsStream("/pom.properties"));
			BHBotVersion = properties.getProperty("version");
		} catch (IOException e) {
			logger.error("Impossible to get pom.properties from jar");
			logger.error(Throwables.getStackTraceAsString(e));
			BHBotVersion = "UNKNOWN";
		}

		try {
			logger.info(PROGRAM_NAME + " v" + BHBotVersion + " build on " + new Date(Misc.classBuildTimeMillis()) + " started.");
		} catch (URISyntaxException e) {
            logger.info(PROGRAM_NAME + " v" + BHBotVersion + " started. Unknown build date.");
		}

		Properties gitPropertis = Misc.getGITInfo();
		logger.info("GIT commit id: " + gitPropertis.get("git.commit.id") + "  time: " + gitPropertis.get("git.commit.time")) ;

		logger.info("Settings loaded from file");

		settings.checkDeprecatedSettings();
		settings.sanitizeSetting();

		if (!settings.username.equals("") && !settings.username.equals("yourusername")) {
		logger.info("Character: " + settings.username);
		}

		MainThread.loadCues();

		if (!checkPaths()) return;

		processCommand("start");
		
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		while (!finished) {
	        String s;
			try {
				//System.out.print("> ");
				s = br.readLine();
			} catch (IOException e) {
				logger.error("Impossible to read user input");
				logger.error(Throwables.getStackTraceAsString(e));
				return;
			}
			try {
				logger.info("User command: <" + s + ">");
				processCommand(s);
			} catch (Exception e) {
				logger.error("Impossile to process user command: " + s);
				logger.error(Throwables.getStackTraceAsString(e));
			}
		}

		if (mainThread.isAlive()) {
			try {
				// wait for 10 seconds for the main thread to terminate
				logger.info("Waiting for main thread to finish... (timeout=10s)");
				mainThread.join(10*MainThread.SECOND);
			} catch (InterruptedException e) {
				logger.error(Throwables.getStackTraceAsString(e));
			}
			if (mainThread.isAlive()) {
				logger.warn("Main thread is still alive. Force stopping it now...");
				mainThread.interrupt();
				try {
					mainThread.join(); // until thread stops
				} catch (InterruptedException e) {
					logger.error(Throwables.getStackTraceAsString(e));
				}
			}
		}
		logger.info(PROGRAM_NAME + " has finished.");
	}
	
	private static void processCommand(String c) {
		String[] params = c.split(" ");
		switch (params[0]) {
			case "c": { // detect cost from screen
				main.readScreen();
				int current = main.detectCost();
				logger.info("Detected cost: " + current);

				if (params.length > 1) {
					int goal = Integer.parseInt(params[1]);
					logger.info("Goal cost: " + goal);
					boolean result = main.selectCost(current, goal);
					logger.info("Cost change result: " + result);
				}
				break;
			}
			case "crash": {
				int i = 3 / 0;
				break;
			}
			case "d": { // detect difficulty from screen
				main.readScreen();
				int current = main.detectDifficulty();
				logger.info("Detected difficulty: " + current);

				if (params.length > 1) {
					int goal = Integer.parseInt(params[1]);
					logger.info("Goal difficulty: " + goal);
					boolean result = main.selectDifficulty(current, goal);
					logger.info("Difficulty change result: " + result);
				}
				break;
			}
			case "do":
				switch (params[1]) {
					case "raid":
						// force raid (if we have at least 1 shard though)
						logger.info("Forcing raid...");
						scheduler.doRaidImmediately = true;
						break;
					case "expedition":
						// force dungeon (regardless of energy)
						logger.info("Forcing expedition...");
						scheduler.doExpeditionImmediately = true;
						break;
					case "dungeon":
						// force dungeon (regardless of energy)
						logger.info("Forcing dungeon...");
						scheduler.doDungeonImmediately = true;
						break;
					case "gauntlet":
					case "trials":
						// force 1 run of gauntlet/trials (regardless of tokens)
						logger.info("Forcing gauntlet/trials...");
						scheduler.doTrialsOrGauntletImmediately = true;
						break;
					case "pvp":
						// force pvp
						logger.info("Forcing PVP...");
						scheduler.doPVPImmediately = true;
						break;
					case "gvg":
						// force gvg
						logger.info("Forcing GVG...");
						scheduler.doGVGImmediately = true;
						break;
					case "invasion":
						// force invasion
						logger.info("Forcing invasion...");
						scheduler.doInvasionImmediately = true;
						break;
					case "worldboss":
						// force invasion
						logger.info("Forcing World Boss...");
						scheduler.doWorldBossImmediately = true;
						break;
					default:
						logger.warn("Unknown dungeon : '" + params[1] + "'");
						break;
				}
				break;
			case "exit":
			case "quit":
			case "stop":
				main.finished = true;
				finished = true;
				break;
			case "hide":
				main.hideBrowser();
				settings.hideWindowOnRestart = true;
				break;
			case "load":
				MainThread.loadCookies();
				break;
			case "loadsettings":
				String file = Settings.DEFAULT_SETTINGS_FILE;
				if (params.length > 1)
					file = params[1];
				settings.load(file);
				settings.checkDeprecatedSettings();
				settings.sanitizeSetting();
				reloadLogger();
				break;
			case "pause":
				if (params.length > 1) {
					int pauseDuration = Integer.parseInt(params[1]) * MainThread.MINUTE;
					scheduler.pause(pauseDuration);
				} else {
					scheduler.pause();
				}
				break;
			case "plan":
				settings.load("plans/" + params[1] + ".ini");
				settings.checkDeprecatedSettings();
				settings.sanitizeSetting();
				reloadLogger();
				logger.info("Plan loaded from " + "<plans/" + params[1] + ".ini>.");
				break;
			case "pomessage":
				String message = "Test message from BHbot!";

				// We split on spaces so we re-build the original message
				if (params.length > 1)
					message = String.join(" ", Arrays.copyOfRange(params, 1, params.length));

				if (settings.enablePushover) {
					String poLogMessage = "Sending Pushover test message.";
					poLogMessage += "\n\n poUserToken is: " + settings.poUserToken;
					poLogMessage += "\n poAppToken is: " + settings.poAppToken;
					logger.info(poLogMessage);

					String poScreenName = main.saveGameScreen("pomessage");
					File poScreenFile = new File(poScreenName);

					main.sendPushOverMessage("Test Notification", message, MessagePriority.NORMAL, poScreenFile);
					if (!poScreenFile.delete()) logger.warn("Impossible to delete tmp img for pomessage command.");

				} else {
					logger.warn("Pushover integration is disabled in the settings!");
				}
				break;
			case "print":
				switch (params[1]) {
					case "familiars":
					case "familiar":
					case "fam":
						MainThread.printFamiliars();
						break;
					case "version":
						try {
							logger.info(PROGRAM_NAME + " v" + BHBotVersion + " build on " + new Date(Misc.classBuildTimeMillis()) + " started.");
						} catch (URISyntaxException e) {
							logger.info(PROGRAM_NAME + " v" + BHBotVersion + " started. Unknown build date.");
						}

						Properties gitPropertis = Misc.getGITInfo();
						logger.info("GIT commit id: " + gitPropertis.get("git.commit.id") + "  time: " + gitPropertis.get("git.commit.time")) ;
						break;
					default:
						logger.warn("Impossible to print : '" + params[1] +"'");
						break;
				}
				break;
			case "restart":
				main.restart(false);
				break;
			case "save":
				MainThread.saveCookies();
				break;
			case "shot":
				String fileName = "shot";
				if (params.length > 1)
					fileName = params[1];

				main.saveGameScreen(fileName);

				logger.info("Screenshot '" + fileName + "' saved.");
				break;
			case "start":
				main = new MainThread();
				mainThread = new Thread(main, "MainThread");
				mainThread.start();
				break;
			case "readouts":
			case "resettimers":
				main.resetTimers();
				logger.info("Readout timers reset.");
				break;
			case "reload":
				settings.load();
				reloadLogger();
				logger.info("Settings reloaded from disk.");
				break;
			case "resume":
				scheduler.resume();
				break;
			case "set": {
				List<String> list = new ArrayList<>();
				int i = c.indexOf(" ");
				if (i == -1)
					return;
				list.add(c.substring(i + 1));
				settings.load(list);
				settings.checkDeprecatedSettings();
				settings.sanitizeSetting();
				reloadLogger();
				logger.info("Settings updated manually: <" + list.get(0) + ">");
				break;
			}
			case "show":
				main.showBrowser();
				settings.hideWindowOnRestart = false;
				break;
            case "test":
                switch (params[1]) {
                    case "ai":
                    case "autoignore":
                    	boolean ignoreBoss = false;
                    	boolean ignoreShrines = false;

                    	if (params.length > 2) {
                    		switch (params[2].toLowerCase()) {
								case "off":
								case "0":
								case "no":
								case "do":
									ignoreBoss = false;
									break;
								case "on":
								case "1":
								case "yes":
								case "y":
									ignoreBoss = true;
									break;
							}
						}

						if (params.length > 3) {
							switch (params[3].toLowerCase()) {
								case "off":
								case "0":
								case "no":
								case "do":
									ignoreShrines = false;
									break;
								case "on":
								case "1":
								case "yes":
								case "y":
									ignoreShrines = true;
									break;
							}
						}
                        if (!main.checkShrineSettings(ignoreBoss, ignoreShrines)) {
                        	logger.error("Something went wrong when checking auto ignore settings!");
						}
                        break;
                    case "r":
                    case "raidread":
                        main.raidReadTest();
                        break;
                    case "e":
                    case "expeditionread":
                        main.expeditionReadTest();
                        break;
                    case "wb":
                    case "worldboss":
                        main.wbTest();
                        break;
                    case "d":
                        main.updateActivityCounter("World Boss");
                        break;
                    case "ad":
                        main.trySkippingAd();
                        break;
                    default:
                        break;
                }
                break;
            default:
            	logger.warn("Unknown command: '" + c + "'");
            	break;
		}
	}

	private static boolean checkPaths() {
		File chromiumExe = new File(chromiumExePath);
		File chromeDriverExe = new File(chromeDriverExePath);
		File cuePath = new File(cuesPath);
		File screenPath = new File(screenshotPath);

		if (!chromiumExe.exists()) {
			logger.fatal("Impossible to find Chromium executable in path " + chromiumExePath + ". Bot will be stopped!");
			return false;
		} else {
			try {
				logger.debug("Found Chromium in " + chromiumExe.getCanonicalPath());
			} catch (IOException e) {
				logger.error("Error while getting Canonical Path for Chromium");
				logger.error(Throwables.getStackTraceAsString(e));
			}
		}

		if (!chromeDriverExe.exists()) {
			logger.fatal("Impossible to find chromedriver executable in path " + chromeDriverExePath + ". Bot will be stopped!");
			return false;
		} else {
			try {
				logger.debug("Found chromedriver in " + chromeDriverExe.getCanonicalPath());
			} catch (IOException e) {
				logger.error("Error while getting Canonical Path for chromedriver");
				logger.error(Throwables.getStackTraceAsString(e));
			}
		}

		if (!screenPath.exists()) {
			if (!screenPath.mkdir()) {
				logger.fatal("Impossible to create screenshot folder in " + screenshotPath);
				return false;
			} else {
				try {
					logger.info("Created screenshot folder in " + screenPath.getCanonicalPath());
				} catch (IOException e) {
					logger.error("Error while getting Canonical Path for newly created screenshots");
					logger.error(Throwables.getStackTraceAsString(e));
				}
			}
		} else {
			try {
				logger.debug("Found screenshots in " + screenPath.getCanonicalPath());
			} catch (IOException e) {
				logger.error("Error while getting Canonical Path for screenshots");
				logger.error(Throwables.getStackTraceAsString(e));
			}
		}

		if (cuePath.exists() && !cuePath.isFile()) {
			try {
				logger.warn("Found cues in '" + cuePath.getCanonicalPath() +
						"'. This folder is no longer required as all the cues are now part of the jar file.");
			} catch (IOException e) {
				logger.error("Error while checking cues folder");
				logger.error(Throwables.getStackTraceAsString(e));
			}
		}

		return true;
	}

	private static void reloadLogger( ) {
		ConfigurationFactory configFactory = new BHBotConfigurationFactory();
		ConfigurationFactory.setConfigurationFactory(configFactory);
		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		ctx.start(configFactory.getConfiguration(ctx, ConfigurationSource.NULL_SOURCE));
	}

}
