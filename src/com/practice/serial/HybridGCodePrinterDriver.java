package com.practice.serial;

import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;

import java.awt.Color;
import java.awt.DisplayMode;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;
import javax.swing.JFrame;


public class HybridGCodePrinterDriver {
	public static CommPortIdentifier findCommPort(String suggestion) {
		CommPortIdentifier foundIdentifier = null;
		if (suggestion != null) {
			try {
				foundIdentifier = CommPortIdentifier.getPortIdentifier(suggestion);
				if (foundIdentifier.getPortType() != CommPortIdentifier.PORT_SERIAL) {
					System.out.println("Your suggested CommPort:" + suggestion + " is not a serial port. I'll try to find some for you.");
				} else {
					return foundIdentifier;
				}
			} catch (NoSuchPortException e) {
				System.out.println("I took your suggestion for a comport:" + suggestion + ", but it didn't exist. I'll try to find some others");
			}
		}
		
		Enumeration<CommPortIdentifier> identifiers = CommPortIdentifier.getPortIdentifiers();
		while (identifiers.hasMoreElements()) {
			CommPortIdentifier ident = identifiers.nextElement();
			if (ident.getPortType() == CommPortIdentifier.PORT_SERIAL) {
				if (ident.isCurrentlyOwned()) {
					System.out.println("CommPort:" + ident.getName() + " found, but owned by:" + ident.getCurrentOwner());
				} else if (foundIdentifier == null) {
					System.out.println("Found and assigned printer to CommPort:" + ident.getName());
					foundIdentifier = ident;
				} else {
					System.out.println("WARNING: Another CommPort found:" + ident.getName() + " but I didn't pick this one.  Hopefully your printer wasn't connected to this port.");
				}
			}
		}
		
		return foundIdentifier;
	}
	
	public static void main(String[] args) {
		String ownerName = HybridGCodePrinterDriver.class.getName();
		String extension = ".png";
		String commPortName = null;//"/dev/ttyACM0";//null;
		int timeoutWaitingForComPortToRespond = 2000;
		int commPortSpeed = 9600;//TODO: Is this the correct speed?
		boolean useEOLsInGCodeOutput = false;	//TODO: Does gcode require EOL separators?
		SerialPort commPort = null;
		File file = new File("C:\\workspace\\stilfile.zip");
		
		CommPortIdentifier commPortIdentifier = findCommPort(commPortName);
		if (commPortIdentifier == null) {
			System.out.println("No commports available. Have you plugged in your printer?");
			System.exit(1);
		}
		
		try {
			commPort = (SerialPort)commPortIdentifier.open(ownerName, timeoutWaitingForComPortToRespond);
			commPort.setSerialPortParams(commPortSpeed, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
		} catch (PortInUseException e) {
			System.out.println("This commport:" + commPortName + " was already in use by:" + e.currentOwner);
			if (e.currentOwner.equals(ownerName)) {
				System.out.println("Hey wait, that's me! Please shut everything down and try again.");
			}
			System.exit(2);
		} catch (UnsupportedCommOperationException e) {
			System.out.println("The commport parameters you setup were incorrect for this printer:" + e.getMessage());
			System.exit(3);
		}
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();  
		GraphicsDevice device = ge.getDefaultScreenDevice(); 
		
		OutputStream printerOutput = null;
		Window window = null;
		Graphics2D graphicsContext = null;
		BufferedReader gcodeStream = null;
		ZipFile zipFile = null;
		BufferedImage bimage = null;
		InputStream imageStream = null;
		Integer xyResolutionFound[] = new Integer[2];
		Integer xyPixelOffsetFound[] = new Integer[2];
		try {
			printerOutput = commPort.getOutputStream();
			String fileName = file.getName().substring(0, file.getName().indexOf('.'));
			zipFile = new ZipFile(file);
			gcodeStream = new BufferedReader(new InputStreamReader(zipFile.getInputStream(zipFile.getEntry(fileName + ".slice" + "/" + fileName + ".gcode"))));
			String currentLine;
			Integer sliceCount = null;
			Pattern slicePattern = Pattern.compile("\\s*;\\s*<\\s*Slice\\s*>\\s*(\\d+|blank)\\s*", Pattern.CASE_INSENSITIVE);
			Pattern delayPattern = Pattern.compile("\\s*;\\s*<\\s*Delay\\s*>\\s*(\\d+)\\s*", Pattern.CASE_INSENSITIVE);
			Pattern sliceCountPattern = Pattern.compile("\\s*;\\s*Number\\s*of\\s*Slices\\s*=\\s*(\\d+)\\s*", Pattern.CASE_INSENSITIVE);
			Pattern resolutionPattern = Pattern.compile("\\s*;\\s*\\(\\s*(X|Y)\\s*Resolution\\s*=\\s*(\\d+)\\s*px\\s*\\)\\s*", Pattern.CASE_INSENSITIVE);
			Pattern pixelOffsetPattern = Pattern.compile("\\s*;\\s*\\(\\s*(X|Y)\\s*Pixel\\s*Offset\\s*=\\s*(\\d+)\\s*px\\s*\\)\\s*", Pattern.CASE_INSENSITIVE);
			while ((currentLine = gcodeStream.readLine()) != null) {
				Matcher matcher = slicePattern.matcher(currentLine);
				if (matcher.matches()) {
					if (sliceCount == null) {
						throw new IllegalArgumentException("No 'Number of Slices' line in gcode file");
					}
					
					if (graphicsContext == null) {
						window = new JFrame(); 
						device.setFullScreenWindow(window); 
						graphicsContext = (Graphics2D) window.getGraphics(); 
					}
					if (matcher.group(1).toUpperCase().equals("BLANK")) {
						if (bimage != null) {
							bimage.flush();
						}
						if (imageStream != null) {
							try {
								imageStream.close();
							} catch (IOException e) {}
						}
						graphicsContext.setBackground(Color.BLACK);
						graphicsContext.clearRect(0, 0, (int)device.getFullScreenWindow().getSize().getWidth(), (int)device.getFullScreenWindow().getSize().getHeight());
					} else {
						int imageIndex = Integer.parseInt(matcher.group(1));
						imageStream = zipFile.getInputStream(zipFile.getEntry(fileName + ".slice" + "/" + fileName + String.format("%0" + ((sliceCount + "").length() + 1) + "d", imageIndex) + extension));
						bimage = ImageIO.read(imageStream);
						graphicsContext.drawImage(bimage, null, xyPixelOffsetFound[0] != null?xyPixelOffsetFound[0]:0, xyPixelOffsetFound[1] != null?xyPixelOffsetFound[1]:0);
					}
					continue;
				}
				
				matcher = delayPattern.matcher(currentLine);
				if (matcher.matches()) {
					try {
						Thread.sleep(Integer.parseInt(matcher.group(1)));
					} catch (InterruptedException e) {
						e.printStackTrace();
					}//*/
					continue;
				}
				
				matcher = sliceCountPattern.matcher(currentLine);
				if (matcher.matches()) {
					sliceCount = Integer.parseInt(matcher.group(1));
					continue;
				}
				
				matcher = resolutionPattern.matcher(currentLine);
				if (matcher.matches()) {
					if (matcher.group(1).toUpperCase().equals("X")) {
						xyResolutionFound[0] = Integer.parseInt(matcher.group(2));
					} else {
						xyResolutionFound[1] = Integer.parseInt(matcher.group(2));
					}
					
					if (xyResolutionFound[0] != null && xyResolutionFound[1] != null && device.isDisplayChangeSupported()) {
						DisplayMode dm = new DisplayMode(xyResolutionFound[0], xyResolutionFound[1], DisplayMode.BIT_DEPTH_MULTI, DisplayMode.REFRESH_RATE_UNKNOWN );
						device.setDisplayMode(dm);
						window = new JFrame();
						device.setFullScreenWindow(window);
						graphicsContext = (Graphics2D) window.getGraphics();
					}
	                continue;
				}
				
				matcher = pixelOffsetPattern.matcher(currentLine);
				if (matcher.matches()) {
					if (matcher.group(1).toUpperCase().equals("X")) {
						xyPixelOffsetFound[0] = Integer.parseInt(matcher.group(2));
					} else {
						xyPixelOffsetFound[1] = Integer.parseInt(matcher.group(2));
					}
	                continue;
				}
				
				printerOutput.write(currentLine.getBytes());
				if (useEOLsInGCodeOutput) {
					printerOutput.write(System.lineSeparator().getBytes());
				}
				
				//TODO: Optimize to read from printer instead of waiting for an arbitrary 2 second time period above
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (gcodeStream != null) {
				try {
					gcodeStream.close();
				} catch (IOException e) {}
			}
			if (zipFile != null) {
				try {
					zipFile.close();
				} catch (IOException e) {}
			}
			if (window != null) {
				window.dispose(); 
			}
			if (graphicsContext != null) {
				graphicsContext.dispose(); 
			}
			if (printerOutput != null) {
				try {
					printerOutput.close();
				} catch (IOException e) {}
			}
			if (commPort != null) {
				commPort.close();
			}
			
		}
		
	}
}
