package com.datacollectorlocal;


import java.awt.Image;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;



import javax.xml.bind.DatatypeConverter;

















import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.apache.tomcat.util.http.fileupload.IOUtils;
//import org.apache.tomcat.jni.Thread;
import org.jnativehook.GlobalScreen;
import org.jnativehook.NativeHookException;
import org.jnativehook.NativeInputEvent;
//import org.jnativehook.dispatcher.SwingDispatchService;
import org.jnativehook.keyboard.NativeKeyEvent;
import org.jnativehook.keyboard.NativeKeyListener;
import org.jnativehook.mouse.NativeMouseEvent;
import org.jnativehook.mouse.NativeMouseInputListener;
import org.jnativehook.mouse.NativeMouseWheelEvent;
import org.jnativehook.mouse.NativeMouseWheelListener;

import com.google.gson.Gson;



public class Start implements NativeMouseInputListener, NativeKeyListener, Runnable, ScreenshotListener
{
	boolean verbose = false;
	
	private Thread myThread;
	private ActiveWindowMonitor myMonitor = new ActiveWindowMonitor();
	private String windowID = "";
	private String windowName = "";
	private int windowX = -1;
	private int windowY = -1;
	private int windowWidth = -1;
	private int windowHeight = -1;
	private HashMap currentWindowData = null;
	private ConcurrentLinkedQueue windowsToWrite = new ConcurrentLinkedQueue();
	private ConcurrentLinkedQueue clicksToWrite = new ConcurrentLinkedQueue();
	private ConcurrentLinkedQueue screenshotsToWrite = new ConcurrentLinkedQueue();
	private ConcurrentLinkedQueue keysToWrite = new ConcurrentLinkedQueue();
	private ConcurrentLinkedQueue processesToWrite = new ConcurrentLinkedQueue();
	private int screenshotTimeout = 10000;
	private ScreenshotGenerator myGenerator;
	private int processTimeout = 20000;
	private ProcessMonitor myProcessMonitor;
	private boolean running = false;
	private TestingConnectionSource connectionSource = new TestingConnectionSource();
	
	private DataAggregator curAggregator = null;
	
	private String userName = "default";
	private String sessionToken = "";
	
	private static String curVersion = "";
	
	private static String serverAddress = "localhost:8080";
	
	private static double timeDifference = 0;
	
	private static final String jarPath = (Start.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toString();
	private static final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
	private static Start myStart = null;
	private boolean cleanedUp = false;
	
	private static String[] programArgs = new String[0];
	private static boolean hasBeenLaunched = false;
	
	public Start(String user)
	{
		sessionToken = UUID.randomUUID().toString();
		userName = user;
		myThread = new Thread(this);
		myThread.start();
		try
		{
			Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
			logger.setLevel(Level.OFF);
			GlobalScreen.registerNativeHook();
			GlobalScreen.addNativeMouseListener(this);
			GlobalScreen.addNativeKeyListener(this);
		}
		catch(NativeHookException e)
		{
			e.printStackTrace();
		}
		myGenerator = new ScreenshotGenerator(screenshotTimeout);
		myGenerator.addScreenshotListener(this);
		myProcessMonitor = new ProcessMonitor(processTimeout);
		myProcessMonitor.setStart(this);
	}
	
	public static synchronized void update()
	{
		boolean update = checkVersion();
		if(update)
		{
			System.out.println("Updated, now restarting");
			restart();
		}
	}
	
	public static void restart()
	{
		File currentFile = new File(jarPath);
		if(!currentFile.isFile())
		{
			System.out.println("Warning: Not running from JAR");
		}
		else
		{
			if(myStart != null)
			{
				myStart.stop();
				while(myStart.cleanedUp == false)
				{
					try
					{
						Thread.sleep(500);
					}
					catch(InterruptedException e)
					{
						e.printStackTrace();
					}
				}
			}
			System.out.println("Current execution cleaned up");
			
			
			ArrayList<String> processList = new ArrayList<String>();
			processList.add(javaBin);
			processList.add("-jar");
			processList.add(jarPath);
			for(String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments())
			{
				processList.add(jvmArg);
			}
			for(String arg : programArgs)
			{
				processList.add(arg);
			}
			
			System.out.println("Launching with: " + processList);
			ProcessBuilder processBuilder = new ProcessBuilder(processList);
			processBuilder.inheritIO();
			try
			{
				Process myProcess = processBuilder.start();
				System.out.println("Done launching");
				myProcess.waitFor();
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
			System.exit(0);
		}
	}
	
	public static boolean checkVersion()
	{
		boolean updated = false;
		File currentFile = new File(jarPath);
		//System.out.println(jarPath);
		//System.out.println(Arrays.toString(currentFile.list()));
		if(!currentFile.isFile())
		{
			System.out.println("Warning: Not running from JAR");
		}
		else
		{
			try
			{
				MessageDigest md = MessageDigest.getInstance("MD5");
				if(Paths.get(jarPath).toFile().length() > 1024*1024*10)
				{
					System.out.println("File is " + Paths.get(jarPath).toFile().length());
					throw new Exception("This file is too big");
				}
				byte[] b = Files.readAllBytes(Paths.get(jarPath));
				byte[] hashBytes = MessageDigest.getInstance("MD5").digest(b);
				curVersion = DatatypeConverter.printHexBinary(hashBytes);
				//System.out.println("Running version: " + curVersion);
				String fullCheck = "http://" + serverAddress + "/DataCollectorServer/endpointSoftware/currentVersion.jsp";
				//System.out.println("Checking for update at: " + fullCheck);
				HttpClient http = new DefaultHttpClient();
				HttpGet checkGet = new HttpGet(fullCheck);
				double myTime = System.currentTimeMillis();
				
				HttpResponse checkResponse = http.execute(checkGet);
				HttpEntity checkEntity = checkResponse.getEntity();
				String checkVersion = EntityUtils.toString(checkEntity);
				//System.out.println(checkVersion);
				Gson gson = new Gson();
				HashMap fromJSON = gson.fromJson(checkVersion, HashMap.class);
				//System.out.println(fromJSON);
				
				double serverTime = (Double)fromJSON.get("serverTime");
				timeDifference = myTime - serverTime;
				timeDifference = (int)timeDifference/1000;
				timeDifference *= 1000;
				
				if(fromJSON.get("status").equals("error"))
				{
					return updated;
				}
				//System.out.println("Current time difference is: " + timeDifference);
				
				if(!curVersion.equals(fromJSON.get("currentVersion")))
				{
					//Sleep in case server needs to reload
					System.out.println("Waiting for 15");
					Thread.sleep(15000);
					System.out.println("Current version is not up to date");
					String downloadURL = "http://" + serverAddress + fromJSON.get("downloadURL");
					System.out.println("Downloading new version from: " + downloadURL);
					HttpGet downloadUpdate = new HttpGet(downloadURL);
					HttpResponse downloadResponse = http.execute(downloadUpdate);
					HttpEntity downloadEntity = downloadResponse.getEntity();
					byte[] fileData = EntityUtils.toByteArray(downloadEntity);
					String fileInString = new String(fileData);
					
					//System.out.println("File data recieved: " + fileInString);
					System.out.println("Updating JAR");
					currentFile.delete();
					FileOutputStream updateFileStream = new FileOutputStream(currentFile, false);
					updateFileStream.write(fileData);
					updateFileStream.close();
					updated = true;
					System.out.println("JAR updated");
				}
			}
			catch(Exception e)
			{
				e.printStackTrace();
			}
		}
		return updated;
	}
	
	public synchronized static HashMap configure(String[] args)
	{
		HashMap myReturn = new HashMap();
		for(int x=0; x<args.length; x++)
		{
			if(args[x].equals("-user"))
			{
				myReturn.put("user", args[x+1]);
				x++;
			}
			else if(args[x].equals("-server"))
			{
				myReturn.put("server", args[x+1]);
				x++;
			}
		}
		return myReturn;
	}

	public static void main(String[] args)
	{
		System.out.println("Launching on " + new Date());
		System.out.println("Got args: " + Arrays.toString(args));
		//if(true)
		//	return;
		if(hasBeenLaunched)
		{
			System.out.println("Already running!");
			return;
		}
		hasBeenLaunched = true;
		programArgs = args;
		HashMap configuration = configure(args);
		update();
		//if(true)
		//	return;
		String userToStart = "default";
		
		if(configuration.containsKey("user"))
		{
			userToStart = (String) configuration.get("user");
		}
		if(configuration.containsKey("server"))
		{
			serverAddress = (String) configuration.get("server");
		}
		
		myStart = new Start(userToStart);
		while(myStart.running)
		{
			//Don't give up control
		}
	}
	
	public void setAggregator(DataAggregator myAggregator)
	{
		curAggregator = myAggregator;
	}
	
	public synchronized boolean checkNew(HashMap newWindow)
	{
		if(newWindow == null)
		{
			return false;
		}
		if(!newWindow.get("WindowID").equals(windowID) || !newWindow.get("WindowTitle").equals(windowName) || !((int)newWindow.get("x") == windowX) || !((int)newWindow.get("y") == windowY || !((int)newWindow.get("width") == windowWidth) || !((int)newWindow.get("height") == windowHeight)))
		{
			windowName = (String) newWindow.get("WindowTitle");
			windowID = (String) newWindow.get("WindowID");
			windowX = (int) newWindow.get("x");
			windowY = (int) newWindow.get("y");
			windowWidth = (int) newWindow.get("width");
			windowHeight = (int) newWindow.get("height");
			if(newWindow != null)
			{
				//Calendar currentTime = Calendar.getInstance();
				newWindow.put("clickedInTime", new Timestamp(new Date().getTime()-(int)timeDifference));
				newWindow.put("username", userName);
				windowsToWrite.add(newWindow);
			}
			currentWindowData = newWindow;
			if(verbose)
			System.out.println("New window");
			return true;
		}
		return false;
	}

	
	boolean recordClick = false;
	@Override
	public synchronized void nativeMouseClicked(NativeMouseEvent arg0)
	{
		if(!recordClick)
		{
			return;
		}
		//if(verbose)
		//System.out.println("Mouse Clicked: " + arg0.getX() + ", " + arg0.getY());
		checkNew(myMonitor.getTopWindow());
		HashMap clickToWrite = new HashMap();
		clickToWrite.put("xLoc", arg0.getX());
		clickToWrite.put("yLoc", arg0.getY());
		clickToWrite.put("type", "click");
		//Calendar currentTime = Calendar.getInstance();
		clickToWrite.put("clickTime", new Timestamp(new Date().getTime()-(int)timeDifference));
		clickToWrite.put("window", currentWindowData);
		clickToWrite.put("username", userName);
		clicksToWrite.add(clickToWrite);
	}

	@Override
	public synchronized void nativeMousePressed(NativeMouseEvent arg0)
	{
		//if(verbose)
		//System.out.println("Mouse Pressed: " + arg0.getX() + ", " + arg0.getY());
		checkNew(myMonitor.getTopWindow());
		HashMap clickToWrite = new HashMap();
		clickToWrite.put("xLoc", arg0.getX());
		clickToWrite.put("yLoc", arg0.getY());
		clickToWrite.put("type", "down");
		//Calendar currentTime = Calendar.getInstance();
		clickToWrite.put("clickTime", new Timestamp(new Date().getTime()-(int)timeDifference));
		clickToWrite.put("window", currentWindowData);
		clickToWrite.put("username", userName);
		clicksToWrite.add(clickToWrite);
	}

	@Override
	public synchronized void nativeMouseReleased(NativeMouseEvent arg0)
	{
		//if(verbose)
		//System.out.println("Mouse Released: " + arg0.getX() + ", " + arg0.getY());
		checkNew(myMonitor.getTopWindow());
		HashMap clickToWrite = new HashMap();
		clickToWrite.put("xLoc", arg0.getX());
		clickToWrite.put("yLoc", arg0.getY());
		clickToWrite.put("type", "up");
		//Calendar currentTime = Calendar.getInstance();
		clickToWrite.put("clickTime", new Timestamp(new Date().getTime()-(int)timeDifference));
		clickToWrite.put("window", currentWindowData);
		clickToWrite.put("username", userName);
		clicksToWrite.add(clickToWrite);
	}

	
	private boolean useDragged = false;
	@Override
	public synchronized void nativeMouseDragged(NativeMouseEvent arg0)
	{
		if(!useDragged)
		{
			return;
		}
		//if(verbose)
		//System.out.println("Mouse Dragged: " + arg0.getX() + ", " + arg0.getY());
		checkNew(myMonitor.getTopWindow());
		HashMap clickToWrite = new HashMap();
		clickToWrite.put("xLoc", arg0.getX());
		clickToWrite.put("yLoc", arg0.getY());
		clickToWrite.put("type", "drag");
		//Calendar currentTime = Calendar.getInstance();
		clickToWrite.put("clickTime", new Timestamp(new Date().getTime()-(int)timeDifference));
		clickToWrite.put("window", currentWindowData);
		clickToWrite.put("username", userName);
		clicksToWrite.add(clickToWrite);
	}

	@Override
	public void nativeMouseMoved(NativeMouseEvent arg0)
	{
		//if(verbose)
		//System.out.println("Mouse Moved: " + arg0.getX() + ", " + arg0.getY());
		
	}
	
	@Override
	public synchronized void nativeKeyPressed(NativeKeyEvent arg0)
	{
		
		checkNew(myMonitor.getTopWindow());
		HashMap keyToWrite = new HashMap();
		keyToWrite.put("type", "press");
		keyToWrite.put("inputTime", new Timestamp(new Date().getTime()-(int)timeDifference));
		keyToWrite.put("window", currentWindowData);
		keyToWrite.put("button", NativeKeyEvent.getKeyText(arg0.getKeyCode()));
		if(currentWindowData == null)
		{
			System.err.println(keyToWrite);
		}
		keyToWrite.put("username", userName);
		keysToWrite.add(keyToWrite);
		
	}

	@Override
	public synchronized void nativeKeyReleased(NativeKeyEvent arg0)
	{
		
		checkNew(myMonitor.getTopWindow());
		HashMap keyToWrite = new HashMap();
		keyToWrite.put("type", "release");
		keyToWrite.put("inputTime", new Timestamp(new Date().getTime()-(int)timeDifference));
		keyToWrite.put("window", currentWindowData);
		keyToWrite.put("button", NativeKeyEvent.getKeyText(arg0.getKeyCode()));
		if(currentWindowData == null)
		{
			System.err.println(keyToWrite);
		}
		keyToWrite.put("username", userName);
		keysToWrite.add(keyToWrite);
		
	}
	
	@Override
	public void nativeKeyTyped(NativeKeyEvent arg0)
	{
		//System.out.println("" + arg0.getWhen() + ":" + arg0.getKeyChar());
		//System.out.println(Short.MAX_VALUE * 2);
		//if(true)
		//	return;
		checkNew(myMonitor.getTopWindow());
		HashMap keyToWrite = new HashMap();
		keyToWrite.put("type", "type");
		keyToWrite.put("inputTime", new Timestamp(new Date().getTime()-(int)timeDifference));
		keyToWrite.put("preciseTime", arg0.getWhen());
		keyToWrite.put("window", currentWindowData);
		keyToWrite.put("button", "" + arg0.getKeyChar());
		if(currentWindowData == null)
		{
			System.err.println(keyToWrite);
		}
		keyToWrite.put("username", userName);
		keysToWrite.add(keyToWrite);
	}
	
	public void monitorProcesses(ArrayList processes)
	{
		for(int x=0; x<processes.size(); x++)
		{
			((HashMap)processes.get(x)).put("username", userName);
			processesToWrite.add(processes.get(x));
		}
	}
	
	public void stop()
	{
		GlobalScreen.removeNativeKeyListener(this);
		GlobalScreen.removeNativeMouseListener(this);
		try
		{
			GlobalScreen.unregisterNativeHook();
		}
		catch(NativeHookException e)
		{
			e.printStackTrace();
		}
		myGenerator.stop();
		myProcessMonitor.stop();
		running = false;
	}

	@Override
	public void run()
	{
		running = true;
		//if(true)
		//	return;
		Connection myConnection = connectionSource.getDatabaseConnection();
		
		try
		{
			while(myConnection == null)
			{
				Thread.sleep(5000);
				myConnection = connectionSource.getDatabaseConnection();
			}
			myConnection.setAutoCommit(false);
		}
		catch (Exception e2)
		{
			e2.printStackTrace();
		}
		int count = 0;
		do
		{
			count++;
			try
			{
				if(myConnection == null)
				{
					myConnection = connectionSource.getDatabaseConnection();
					myConnection.setAutoCommit(false);
				}
				if(myConnection.isClosed())
				{
					myConnection = connectionSource.getDatabaseConnection();
					myConnection.setAutoCommit(false);
				}
			}
			catch(SQLException e1)
			{
				
			}
			
			
			try
			{
				checkNew(myMonitor.getTopWindow());
				if(count > 5 && !windowsToWrite.isEmpty() || !clicksToWrite.isEmpty() || !screenshotsToWrite.isEmpty())
				{
					
					if(verbose)
						System.out.println("Recording user JIC");
					
					String userInsert = "INSERT IGNORE INTO `dataCollection`.`User` (`username`, `session`) VALUES ";
					String userRow = "(?,?)";
					userInsert += userRow;
					PreparedStatement userStatement = myConnection.prepareStatement(userInsert);
					userStatement.setString(1, userName);
					userStatement.setString(2, sessionToken);
					userStatement.execute();
					
					
					if(verbose)
						System.out.println("Time to record " + windowsToWrite.size() + " window changes");
					
					int toInsert = windowsToWrite.size();
					int clickToInsert = clicksToWrite.size();
					int screenshotsToInsert = screenshotsToWrite.size();
					int keyToInsert = keysToWrite.size();
					int toInsertProcess = processesToWrite.size();
					ConcurrentLinkedQueue countQueue = new ConcurrentLinkedQueue();
					//ConcurrentLinkedQueue nextQueue = new ConcurrentLinkedQueue();
					
					if(toInsert > 0)
					{
						String processInsert = "INSERT IGNORE INTO `dataCollection`.`Process` (`username`, `session`, `user`, `pid`, `start`, `command`) VALUES ";
						String eachProcessRow = "(?, ?, ?, ?, ?, ?)";
						
						String processArgInsert = "INSERT IGNORE INTO `dataCollection`.`ProcessArgs` (`username`, `session`, `user`, `pid`, `start`, `numbered`, `arg`) VALUES ";
						String eachProcessArgRow = "(?, ?, ?, ?, ?, ?, ?)";
						//int argCount = 0;
						
						String processAttInsert = "INSERT IGNORE INTO `dataCollection`.`ProcessAttributes` (`username`, `session`, `user`, `pid`, `start`, `cpu`, `mem`, `vsz`, `rss`, `tty`, `stat`, `time`, `timestamp`) VALUES ";
						String eachProcessAttRow = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
						
						String windowInsert = "INSERT IGNORE INTO `dataCollection`.`Window` (`username`, `session`, `user`, `pid`, `start`, `xid`, `firstClass`, `secondClass`) VALUES ";
						String eachWindowRow = "(?, ?, ?, ?, ?, ?, ?, ?)";
						
						String windowDetailInsert = "INSERT IGNORE INTO `dataCollection`.`WindowDetails` (`username`, `session`, `user`, `pid`, `start`, `xid`, `x`, `y`, `width`, `height`, `name`, `timeChanged`) VALUES ";
						String eachWindowDetailRow = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
						
						boolean hasArgs = false;
						
						
						boolean argsStarted = false;
						for(int x=0; x<toInsert; x++)
						{
							HashMap tmpMap = (HashMap) windowsToWrite.poll();
							HashMap tmpProcess = (HashMap) tmpMap.get("ProcessInfo");
							ArrayList argList = (ArrayList) tmpProcess.get("ARGS");
							//argCount += argList.size();
							
							for(int y=0; argList != null && y<argList.size(); y++)
							{
								hasArgs = true;
								if(y > 0 || argsStarted)
								{
									processArgInsert += ", " + eachProcessArgRow;
								}
								else
								{
									argsStarted = true;
									processArgInsert += eachProcessArgRow;
								}
								//if(verbose)
								//System.out.println(processArgInsert);
							}
							
							//if(verbose)
							//System.out.println(tmpProcess);
							
							countQueue.add(tmpMap);
							
							if(x > 0)
							{
								processInsert += ", " + eachProcessRow;
								processAttInsert += ", " + eachProcessAttRow;
								windowInsert += ", " + eachWindowRow;
								windowDetailInsert += ", " + eachWindowDetailRow;
							}
							else
							{
								processInsert += eachProcessRow;
								processAttInsert += eachProcessAttRow;
								windowInsert += eachWindowRow;
								windowDetailInsert += eachWindowDetailRow;
							}
						}
						
						if(verbose)
							System.out.println(processInsert);
						if(verbose)
							System.out.println(processAttInsert);
						if(verbose)
							System.out.println(windowInsert);
						if(verbose)
							System.out.println(windowDetailInsert);
						if(verbose)
							System.out.println(processArgInsert);
						
						PreparedStatement processStatement = myConnection.prepareStatement(processInsert);
						PreparedStatement processAttStatement = myConnection.prepareStatement(processAttInsert);
						PreparedStatement windowStatement = myConnection.prepareStatement(windowInsert);
						PreparedStatement windowDetailStatement = myConnection.prepareStatement(windowDetailInsert);
						
						//if(verbose)
						//System.out.println(processArgInsert);
						PreparedStatement processArgStatement = null;
						if(hasArgs)
						{
							processArgStatement = myConnection.prepareStatement(processArgInsert);
						}
						
						int fieldCount = 1;
						int argFieldCount = 1;
						int attFieldCount = 1;
						int windowFieldCount = 1;
						int windowDetailFieldCount = 1;
						for(int x=0; x<toInsert; x++)
						{
							HashMap tmpMap = (HashMap) countQueue.poll();
							
							HashMap tmpProcess = (HashMap) tmpMap.get("ProcessInfo");
							
							processStatement.setString(fieldCount, (String) tmpMap.get("username"));
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("username"));
							windowStatement.setString(windowFieldCount, (String) tmpMap.get("username"));
							windowDetailStatement.setString(windowDetailFieldCount, (String) tmpMap.get("username"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processStatement.setString(fieldCount, sessionToken);
							processAttStatement.setString(attFieldCount, sessionToken);
							windowStatement.setString(windowFieldCount, sessionToken);
							windowDetailStatement.setString(windowDetailFieldCount, sessionToken);
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processStatement.setString(fieldCount, (String) tmpProcess.get("USER"));
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("USER"));
							windowStatement.setString(windowFieldCount, (String) tmpProcess.get("USER"));
							windowDetailStatement.setString(windowDetailFieldCount, (String) tmpProcess.get("USER"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processStatement.setString(fieldCount, (String) tmpProcess.get("PID"));
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("PID"));
							windowStatement.setString(windowFieldCount, (String) tmpProcess.get("PID"));
							windowDetailStatement.setString(windowDetailFieldCount, (String) tmpProcess.get("PID"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processStatement.setString(fieldCount, (String) tmpProcess.get("START"));
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("START"));
							windowStatement.setString(windowFieldCount, (String) tmpProcess.get("START"));
							windowDetailStatement.setString(windowDetailFieldCount, (String) tmpProcess.get("START"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							//processStatement.setString(fieldCount, (String) tmpProcess.get("TIME"));
							//fieldCount++;
							
							processStatement.setString(fieldCount, (String) tmpProcess.get("COMMAND"));
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("%CPU"));
							windowStatement.setString(windowFieldCount, (String) tmpMap.get("WindowID"));
							windowDetailStatement.setString(windowDetailFieldCount, (String) tmpMap.get("WindowID"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("%MEM"));
							windowStatement.setString(windowFieldCount, (String) tmpMap.get("WindowFirstClass"));
							windowDetailStatement.setInt(windowDetailFieldCount, (int) tmpMap.get("x"));
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("VSZ"));
							windowStatement.setString(windowFieldCount, (String) tmpMap.get("WindowSecondClass"));
							windowDetailStatement.setInt(windowDetailFieldCount, (int) tmpMap.get("y"));
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("RSS"));
							windowDetailStatement.setInt(windowDetailFieldCount, (int) tmpMap.get("width"));
							attFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("TTY"));
							windowDetailStatement.setInt(windowDetailFieldCount, (int) tmpMap.get("height"));
							attFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("STAT"));
							windowDetailStatement.setString(windowDetailFieldCount, (String) tmpMap.get("WindowTitle"));
							attFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpProcess.get("TIME"));
							windowDetailStatement.setTimestamp(windowDetailFieldCount, (Timestamp) tmpMap.get("clickedInTime"));
							attFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setTimestamp(attFieldCount, (Timestamp) tmpMap.get("clickedInTime"));
							attFieldCount++;
							
							ArrayList argList = (ArrayList) tmpProcess.get("ARGS");
							for(int y=0; argList != null && y<argList.size(); y++)
							{
								processArgStatement.setString(argFieldCount, (String) tmpMap.get("username"));
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, sessionToken);
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, (String) tmpProcess.get("USER"));
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, (String) tmpProcess.get("PID"));
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, (String) tmpProcess.get("START"));
								argFieldCount++;
								
								//processArgStatement.setString(argFieldCount, (String) tmpProcess.get("TIME"));
								//argFieldCount++;
								
								processArgStatement.setInt(argFieldCount, y);
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, (String) argList.get(y));
								argFieldCount++;
							}
							
							//if(verbose)
							//System.out.println(tmpProcess);
							
							//nextQueue.add(tmpMap);
						}
						
						processStatement.execute();
						processAttStatement.execute();
						windowStatement.execute();
						windowDetailStatement.execute();
						if(hasArgs)
						{
							//if(verbose)
							//System.out.println(processArgStatement);
							processArgStatement.execute();
						}
						
					}
					
					
					ConcurrentLinkedQueue nextProcessQueue = new ConcurrentLinkedQueue();
					//ConcurrentLinkedQueue nextQueue = new ConcurrentLinkedQueue();
					
					if(toInsertProcess > 0)
					{
						String processInsert = "INSERT IGNORE INTO `dataCollection`.`Process` (`username`, `session`, `user`, `pid`, `start`, `command`) VALUES ";
						String eachProcessRow = "(?, ?, ?, ?, ?, ?)";
						
						String processArgInsert = "INSERT IGNORE INTO `dataCollection`.`ProcessArgs` (`username`, `session`, `user`, `pid`, `start`, `numbered`, `arg`) VALUES ";
						String eachProcessArgRow = "(?, ?, ?, ?, ?, ?, ?)";
						//int argCount = 0;
						
						String processAttInsert = "INSERT IGNORE INTO `dataCollection`.`ProcessAttributes` (`username`, `session`, `user`, `pid`, `start`, `cpu`, `mem`, `vsz`, `rss`, `tty`, `stat`, `time`, `timestamp`) VALUES ";
						String eachProcessAttRow = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
						
						
						
						boolean hasArgs = false;
						
						
						boolean argsStarted = false;
						for(int x=0; x<toInsertProcess; x++)
						{
							HashMap tmpProcess = (HashMap) processesToWrite.poll();
							
							ArrayList argList = (ArrayList) tmpProcess.get("ARGS");
							//argCount += argList.size();
							
							for(int y=0; argList != null && y<argList.size(); y++)
							{
								hasArgs = true;
								if(y > 0 || argsStarted)
								{
									processArgInsert += ", " + eachProcessArgRow;
								}
								else
								{
									argsStarted = true;
									processArgInsert += eachProcessArgRow;
								}
								//if(verbose)
								//System.out.println(processArgInsert);
							}
							
							if(verbose)
								System.out.println(tmpProcess);
							
							nextProcessQueue.add(tmpProcess);
							
							if(x > 0)
							{
								processInsert += ", " + eachProcessRow;
								processAttInsert += ", " + eachProcessAttRow;
							}
							else
							{
								processInsert += eachProcessRow;
								processAttInsert += eachProcessAttRow;
							}
						}
						
						if(verbose)
							System.out.println(processInsert);
						if(verbose)
							System.out.println(processAttInsert);
						if(verbose)
							System.out.println(processArgInsert);
						
						PreparedStatement processStatement = myConnection.prepareStatement(processInsert);
						PreparedStatement processAttStatement = myConnection.prepareStatement(processAttInsert);
						
						//if(verbose)
						//System.out.println(processArgInsert);
						PreparedStatement processArgStatement = null;
						if(hasArgs)
						{
							processArgStatement = myConnection.prepareStatement(processArgInsert);
						}
						
						int fieldCount = 1;
						int argFieldCount = 1;
						int attFieldCount = 1;
						int windowFieldCount = 1;
						int windowDetailFieldCount = 1;
						for(int x=0; x<toInsertProcess; x++)
						{
							HashMap tmpMap = (HashMap) nextProcessQueue.poll();
							
							processStatement.setString(fieldCount, (String) tmpMap.get("username"));
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("username"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processStatement.setString(fieldCount, sessionToken);
							processAttStatement.setString(attFieldCount, sessionToken);
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processStatement.setString(fieldCount, (String) tmpMap.get("USER"));
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("USER"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processStatement.setString(fieldCount, (String) tmpMap.get("PID"));
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("PID"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processStatement.setString(fieldCount, (String) tmpMap.get("START"));
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("START"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							//processStatement.setString(fieldCount, (String) tmpProcess.get("TIME"));
							//fieldCount++;
							
							processStatement.setString(fieldCount, (String) tmpMap.get("COMMAND"));
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("%CPU"));
							fieldCount++;
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("%MEM"));
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("VSZ"));
							attFieldCount++;
							windowFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("RSS"));
							attFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("TTY"));
							attFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("STAT"));
							attFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setString(attFieldCount, (String) tmpMap.get("TIME"));
							attFieldCount++;
							windowDetailFieldCount++;
							
							processAttStatement.setTimestamp(attFieldCount, (Timestamp) tmpMap.get("clickedInTime"));
							attFieldCount++;
							
							ArrayList argList = (ArrayList) tmpMap.get("ARGS");
							for(int y=0; argList != null && y<argList.size(); y++)
							{
								processArgStatement.setString(argFieldCount, (String) tmpMap.get("username"));
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, sessionToken);
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, (String) tmpMap.get("USER"));
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, (String) tmpMap.get("PID"));
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, (String) tmpMap.get("START"));
								argFieldCount++;
								
								//processArgStatement.setString(argFieldCount, (String) tmpProcess.get("TIME"));
								//argFieldCount++;
								
								processArgStatement.setInt(argFieldCount, y);
								argFieldCount++;
								
								processArgStatement.setString(argFieldCount, (String) argList.get(y));
								argFieldCount++;
							}
							
							//if(verbose)
							//System.out.println(tmpProcess);
							//System.out.println(tmpMap);
							//nextQueue.add(tmpMap);
						}
						
						//if(verbose)
						//	System.out.println(processStatement);
						//if(verbose)
						//	System.out.println(processAttStatement);
						
						processStatement.execute();
						processAttStatement.execute();
						if(hasArgs)
						{
							//if(verbose)
							//	System.out.println(processArgStatement);
							processArgStatement.execute();
						}
						
					}
					
					//toWrite.clear();
					
					if(verbose)
						System.out.println("Time to record " + clicksToWrite.size() + " mouse clicks");
					if(clickToInsert > 0)
					{
						ConcurrentLinkedQueue nextClickQueue = new ConcurrentLinkedQueue();
						
						String mouseClickInsert = "INSERT IGNORE INTO `dataCollection`.`MouseInput` (`username`, `session`, `user`, `pid`, `start`, `xid`, `timeChanged`, `type`, `xLoc`, `yLoc`, `inputTime`) VALUES ";
						String mouseClickRow = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
						
						for(int x=0; x < clickToInsert; x++)
						{
							HashMap clickMap = (HashMap) clicksToWrite.poll();
							HashMap windowMap = (HashMap) clickMap.get("window");
							if(windowMap == null)
							{
								clickToInsert--;
								//continue;
							}
							else
							{
								nextClickQueue.add(clickMap);
								if(x==0)
								{
									mouseClickInsert += mouseClickRow;
								}
								else
								{
									mouseClickInsert += ", " + mouseClickRow;
								}
							}
						}
						
						if(verbose)
							System.out.println(mouseClickInsert);
						
						PreparedStatement mouseClickStatement = myConnection.prepareStatement(mouseClickInsert);
						
						int mouseClickCount = 1;
						
						
						
						for(int x=0; clickToInsert > 0 && x < clickToInsert; x++)
						{
							HashMap clickMap = (HashMap) nextClickQueue.poll();
							HashMap tmpMap = (HashMap) clickMap.get("window");
							HashMap tmpProcess = (HashMap) tmpMap.get("ProcessInfo");
							
							mouseClickStatement.setString(mouseClickCount, (String) tmpMap.get("username"));
							mouseClickCount++;
							
							mouseClickStatement.setString(mouseClickCount, sessionToken);
							mouseClickCount++;
							
							mouseClickStatement.setString(mouseClickCount, (String) tmpProcess.get("USER"));
							mouseClickCount++;
							
							mouseClickStatement.setString(mouseClickCount, (String) tmpProcess.get("PID"));
							mouseClickCount++;
							
							mouseClickStatement.setString(mouseClickCount, (String) tmpProcess.get("START"));
							mouseClickCount++;
							
							mouseClickStatement.setString(mouseClickCount, (String) tmpMap.get("WindowID"));
							mouseClickCount++;
							
							//if(verbose)
							//System.out.println(tmpMap.get("clickedInTime"));
							mouseClickStatement.setTimestamp(mouseClickCount, (Timestamp) tmpMap.get("clickedInTime"));
							mouseClickCount++;
							
							mouseClickStatement.setString(mouseClickCount, (String) clickMap.get("type"));
							mouseClickCount++;
							
							mouseClickStatement.setInt(mouseClickCount, (int) clickMap.get("xLoc"));
							mouseClickCount++;
							
							mouseClickStatement.setInt(mouseClickCount, (int) clickMap.get("yLoc"));
							mouseClickCount++;
							
							//if(verbose)
							//System.out.println(clickMap.get("clickTime"));
							mouseClickStatement.setTimestamp(mouseClickCount, (Timestamp) clickMap.get("clickTime"));
							mouseClickCount++;
							
						}
						
						if(clickToInsert > 0)
						{
							mouseClickStatement.execute();
						}
					}
					
					
					if(verbose)
						System.out.println("Time to record " + screenshotsToWrite.size() + " screenshots");
					if(screenshotsToInsert > 0)
					{
						ConcurrentLinkedQueue nextScreenshotQueue = new ConcurrentLinkedQueue();
						
						String screenshotInsert = "INSERT IGNORE INTO `dataCollection`.`Screenshot` (`username`, `session`, `taken`, `screenshot`) VALUES ";
						String screenshotRow = "(?, ?, ?, ?)";
						
						for(int x=0; x < screenshotsToInsert; x++)
						{
							Object[] screenshotEntry = (Object[]) screenshotsToWrite.poll();
							if(screenshotEntry == null)
							{
								screenshotsToInsert--;
								//continue;
							}
							else
							{
								nextScreenshotQueue.add(screenshotEntry);
								if(x==0)
								{
									screenshotInsert += screenshotRow;
								}
								else
								{
									screenshotInsert += ", " + screenshotRow;
								}
							}
						}
						
						if(verbose)
							System.out.println(screenshotInsert);
						
						PreparedStatement screenshotStatement = myConnection.prepareStatement(screenshotInsert);
						
						int screenshotCount = 1;
						
						
						
						for(int x=0; screenshotsToInsert > 0 && x < screenshotsToInsert; x++)
						{
							Object[] clickMap = (Object[]) nextScreenshotQueue.poll();
							
							screenshotStatement.setString(screenshotCount, (String) clickMap[2]);
							screenshotCount++;
							
							screenshotStatement.setString(screenshotCount, sessionToken);
							screenshotCount++;
							
							screenshotStatement.setTimestamp(screenshotCount, (Timestamp) clickMap[0]);
							screenshotCount++;
							
							screenshotStatement.setBytes(screenshotCount, (byte[]) clickMap[1]);
							screenshotCount++;
							
							
						}
						
						if(screenshotsToInsert > 0)
						{
							screenshotStatement.execute();
						}
					}
					
					if(verbose)
						System.out.println("Time to record " + keysToWrite.size() + " keyboard activities");
					
					if(keyToInsert > 0)
					{
						String allTyped = "";
						ConcurrentLinkedQueue nextPressQueue = new ConcurrentLinkedQueue();
						
						String keyPressInsert = "INSERT IGNORE INTO `dataCollection`.`KeyboardInput` (`username`, `session`, `user`, `pid`, `start`, `xid`, `timeChanged`, `type`, `button`, `inputTime`) VALUES ";
						String keyPressRow = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
						
						for(int x=0; x < keyToInsert; x++)
						{
							HashMap pressMap = (HashMap) keysToWrite.poll();
							HashMap windowMap = (HashMap) pressMap.get("window");
							if(windowMap == null)
							{
								keyToInsert--;
								//continue;
							}
							else
							{
								nextPressQueue.add(pressMap);
								if(x==0)
								{
									keyPressInsert += keyPressRow;
								}
								else
								{
									keyPressInsert += ", " + keyPressRow;
								}
							}
						}
						
						if(verbose)
							System.out.println(keyPressInsert);
						
						PreparedStatement keyPressStatement = myConnection.prepareStatement(keyPressInsert);
						
						int keyPressCount = 1;
						
						for(int x=0; keyToInsert > 0 && x < keyToInsert; x++)
						{
							HashMap pressMap = (HashMap) nextPressQueue.poll();
							HashMap tmpMap = (HashMap) pressMap.get("window");
							HashMap tmpProcess = (HashMap) tmpMap.get("ProcessInfo");
							
							keyPressStatement.setString(keyPressCount, (String) tmpMap.get("username"));
							keyPressCount++;
							
							keyPressStatement.setString(keyPressCount, sessionToken);
							keyPressCount++;
							
							keyPressStatement.setString(keyPressCount, (String) tmpProcess.get("USER"));
							keyPressCount++;
							
							keyPressStatement.setString(keyPressCount, (String) tmpProcess.get("PID"));
							keyPressCount++;
							
							keyPressStatement.setString(keyPressCount, (String) tmpProcess.get("START"));
							keyPressCount++;
							
							keyPressStatement.setString(keyPressCount, (String) tmpMap.get("WindowID"));
							keyPressCount++;
							
							//if(verbose)
							//System.out.println(tmpMap.get("pressedInTime"));
							keyPressStatement.setTimestamp(keyPressCount, (Timestamp) tmpMap.get("clickedInTime"));
							keyPressCount++;
							
							keyPressStatement.setString(keyPressCount, (String) pressMap.get("type"));
							keyPressCount++;
							
							keyPressStatement.setObject(keyPressCount, pressMap.get("button"));
							keyPressCount++;
							
							//if(verbose)
							//System.out.println(pressMap.get("pressTime"));
							keyPressStatement.setTimestamp(keyPressCount, (Timestamp) pressMap.get("inputTime"));
							keyPressCount++;
							
							//allTyped += pressMap.get("button");
							
							
						}
						
						if(keyToInsert > 0)
						{
							keyPressStatement.execute();
							//System.out.println("Inserting " + allTyped);
							//System.out.println(keyPressStatement);
						}
					}
					
					//presssToWrite.clear();
					count = 0;
					
					myConnection.commit();
				}
				Thread.sleep(1000);
				//if(verbose)
				//System.out.println("Loop");
			}
			catch(Exception e)
			{
				e.printStackTrace();
			}
			if(new Random().nextInt() % 20 == 0)
			{
				update();
				System.out.println("Running in session " + sessionToken);
			}
			System.gc();
		} while(running);
		cleanedUp = true;
		
	}

	@Override
	public void getScreenshotEvent(Date timeTaken, Image screenshot)
	{
		Object[] myPair = new Object[3];
		
		try
		{
			JPEGImageWriteParam jpegParams = new JPEGImageWriteParam(null);
			jpegParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			jpegParams.setCompressionQuality((float) .25);
			ByteArrayOutputStream toByte = new ByteArrayOutputStream();
			ImageOutputStream imageOutput = ImageIO.createImageOutputStream(toByte);
			ImageWriter myWriter = ImageIO.getImageWritersByFormatName("jpg").next();
			myWriter.setOutput(imageOutput);
			
			
			myWriter.write(null, new IIOImage((RenderedImage) screenshot, null, null), jpegParams);
			
			
			myPair[0] = new Timestamp(timeTaken.getTime());
			myPair[1] = toByte.toByteArray();
			myPair[2] = userName;
			screenshotsToWrite.add(myPair);
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}
	}

}