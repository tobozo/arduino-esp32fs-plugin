/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Tool to put the contents of the sketch's "data" subfolder
  into an SPIFFS, LittleFS or FatFS partition image and upload it to an ESP32 MCU

  Copyright (c) 2015 Hristo Gochkov (hristo at espressif dot com)

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package com.esp32.mkspiffs;

import java.util.*;
import java.io.*;

import java.text.SimpleDateFormat;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import javax.swing.JOptionPane;

import processing.app.PreferencesData;
import processing.app.Editor;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Platform;
import processing.app.Sketch;
import processing.app.tools.Tool;
import processing.app.helpers.ProcessUtils;
import processing.app.helpers.PreferencesMap;
import processing.app.debug.TargetPlatform;

import org.apache.commons.codec.digest.DigestUtils;
import processing.app.helpers.FileUtils;

import cc.arduino.files.DeleteFilesOnShutdown;

/**
* Taken from https://www.infoworld.com/article/2071275/when-runtime-exec---won-t.html?page=3
*/
class StreamGobbler extends Thread {
    InputStream is;
    String type;

    StreamGobbler(InputStream is, String type) {
        this.is = is;
        this.type = type;
    }

    public void run() {
      try {
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String line=null;
            while ( (line = br.readLine()) != null)
                System.out.println(type + ">" + line);
      } catch (IOException ioe) {
            ioe.printStackTrace();
      }
    }
}


/**
* Example Tools menu entry.
*/
public class ESP32FS implements Tool {
  Editor editor;


  public void init(Editor editor) {
    this.editor = editor;
  }


  public String getMenuTitle() {
    return "ESP32 Sketch Data Upload";
  }

  private String typefs = "";

  private int listenOnProcess(String[] arguments){
      try {
            Runtime rt = Runtime.getRuntime();
            Process proc = rt.exec(arguments);
            // any error message?
            StreamGobbler errorGobbler = new StreamGobbler(proc.getErrorStream(), "_");

            // any output?
            StreamGobbler outputGobbler = new StreamGobbler(proc.getInputStream(), "-");

            // kick them off
            errorGobbler.start();
            outputGobbler.start();

            // any error???
            int exitVal = proc.waitFor();

        return exitVal;
      } catch (Exception e){
        return -1;
      }
  }

  private void sysExec(final String[] arguments){
    Thread thread = new Thread() {
      public void run() {
        try {
          if(listenOnProcess(arguments) != 0){
            editor.statusError(typefs + " Upload failed!");
          } else {
            editor.statusNotice(typefs + " Image Uploaded");
          }
        } catch (Exception e){
          editor.statusError(typefs + " Upload failed!");
        }
      }
    };
    thread.start();
  }

  private String getBuildFolderPath(Sketch s) {
    // first of all try the getBuildPath() function introduced with IDE 1.6.12
    // see commit arduino/Arduino#fd1541eb47d589f9b9ea7e558018a8cf49bb6d03
    try {
      String buildpath = s.getBuildPath().getAbsolutePath();
      return buildpath;
    }
    catch (IOException er) {
      editor.statusError(er);
    }
    catch (Exception er) {
      try {
        File buildFolder = FileUtils.createTempFolder("build", DigestUtils.md5Hex(s.getMainFilePath()) + ".tmp");
        return buildFolder.getAbsolutePath();
      }
      catch (IOException e) {
        editor.statusError(e);
      }
      catch (Exception e) {
        // Arduino 1.6.5 doesn't have FileUtils.createTempFolder
        // String buildPath = BaseNoGui.getBuildFolder().getAbsolutePath();
        java.lang.reflect.Method method;
        try {
          method = BaseNoGui.class.getMethod("getBuildFolder");
          File f = (File) method.invoke(null);
          return f.getAbsolutePath();
        } catch (SecurityException ex) {
          editor.statusError(ex);
        } catch (IllegalAccessException ex) {
          editor.statusError(ex);
        } catch (InvocationTargetException ex) {
          editor.statusError(ex);
        } catch (NoSuchMethodException ex) {
          editor.statusError(ex);
        }
      }
    }
    return "";
  }

  private long parseInt(String value){
    if(value.endsWith("m") || value.endsWith("M")) return 1024*1024*Long.decode(value.substring(0, (value.length() - 1)));
    else if(value.endsWith("k") || value.endsWith("K")) return 1024*Long.decode(value.substring(0, (value.length() - 1)));
    else return Long.decode(value);
  }

  private long getIntPref(String name){
    String data = BaseNoGui.getBoardPreferences().get(name);
    if(data == null || data.contentEquals("")) return 0;
    return parseInt(data);
  }

  private void createAndUpload(){
    long spiStart = 0, spiSize = 0, spiPage = 256, spiBlock = 4096, spiOffset = 0;
    String partitions = "";

    if (typefs == "FatFS") spiOffset = 4096;

    String chip = getChip();

    System.out.println("Chip : " + chip);

    if(!PreferencesData.get("target_platform").contains("esp32")){
      System.err.println();
      editor.statusError(typefs + " Not Supported on "+PreferencesData.get("target_platform"));
      return;
    }

    TargetPlatform platform = BaseNoGui.getTargetPlatform();

    String toolExtension = ".py";
    if(PreferencesData.get("runtime.os").contentEquals("windows")) {
      toolExtension = ".exe";
    }

    String pythonCmd;
    if(PreferencesData.get("runtime.os").contentEquals("windows"))
        pythonCmd = "python.exe";
    else
        pythonCmd = "python3";

    String mkspiffsCmd;
    if(PreferencesData.get("runtime.os").contentEquals("windows"))
    if (typefs == "LittleFS") mkspiffsCmd = "mklittlefs.exe";
        else if (typefs == "FatFS") mkspiffsCmd = "mkfatfs.exe";
    else mkspiffsCmd = "mkspiffs.exe";
    else
        if (typefs == "LittleFS") mkspiffsCmd = "mklittlefs";
        else if (typefs == "FatFS") mkspiffsCmd = "mkfatfs";
    else mkspiffsCmd = "mkspiffs";

    String espotaCmd = "espota.py";
    if(PreferencesData.get("runtime.os").contentEquals("windows"))
        espotaCmd = "espota.exe";

    Boolean isNetwork = false;
    File espota = new File(platform.getFolder()+"/tools");
    File esptool = new File(platform.getFolder()+"/tools");
    String serialPort = PreferencesData.get("serial.port");

    if(!BaseNoGui.getBoardPreferences().containsKey("build.partitions")){
      System.err.println();
      editor.statusError("Partitions Not Defined for "+BaseNoGui.getBoardPreferences().get("name"));
      return;
    }

    File partitionsFile = new File(editor.getSketch().getFolder(), "partitions.csv");

    if (partitionsFile.exists() && partitionsFile.isFile()) {
        System.out.println("Using partitions.csv from sketch folder.");

    } else {
        System.out.println("Using partition scheme from Arduino IDE.");
        try {
          partitions = BaseNoGui.getBoardPreferences().get("build.partitions");
          if(partitions == null || partitions.contentEquals("")){
            editor.statusError("Partitions Not Found for "+BaseNoGui.getBoardPreferences().get("name"));
            return;
          }
        } catch(Exception e){
          editor.statusError(e);
          return;
        }

        partitionsFile = new File(platform.getFolder() + "/tools/partitions", partitions + ".csv");

        if (!partitionsFile.exists() || !partitionsFile.isFile()) {
          System.err.println();
          editor.statusError(typefs + " Error: partitions file " + partitions + ".csv not found!");
          return;
        }
    }

    try {
      BufferedReader partitionsReader = new BufferedReader(new FileReader(partitionsFile));
      String partitionsLine = "";
      long spiPrevEnd = 0;
      boolean isDataLine = false;
      while ((partitionsLine = partitionsReader.readLine()) != null) {
          if (!partitionsLine.substring(0, 1).equals("#")) {
            if( ((typefs != "FatFS") && partitionsLine.contains("spiffs")) || ((typefs == "FatFS") && partitionsLine.contains("ffat"))){
                isDataLine = true;
            }
            partitionsLine = partitionsLine.substring(partitionsLine.indexOf(",")+1);
            partitionsLine = partitionsLine.substring(partitionsLine.indexOf(",")+1);
            partitionsLine = partitionsLine.substring(partitionsLine.indexOf(",")+1);
            while(partitionsLine.startsWith(" ")) partitionsLine = partitionsLine.substring(1);
            String pStart = partitionsLine.substring(0, partitionsLine.indexOf(","));
            partitionsLine = partitionsLine.substring(partitionsLine.indexOf(",")+1);
            while(partitionsLine.startsWith(" ")) partitionsLine = partitionsLine.substring(1);
            String pSize = partitionsLine.substring(0, partitionsLine.indexOf(","));

            //System.out.println("From CSV, Partition Start: " + pStart + ", Size: " + pSize);

            if (isDataLine) {
                if (pStart == null || pStart.trim().isEmpty()) {
                    spiStart = spiPrevEnd + spiOffset;
                } else {
                    spiStart = parseInt(pStart) + spiOffset;
                }
                spiSize = parseInt(pSize) - spiOffset;
                break;
            } else {
                if (pSize != null && !pSize.trim().isEmpty()) {
                    if (pStart == null || pStart.trim().isEmpty()) {
                        spiPrevEnd += parseInt(pSize);
                    } else {
                        spiPrevEnd = parseInt(pStart) + parseInt(pSize);
                    }
                }
                spiSize = 0;
            }
        }
      }
      if(spiSize == 0){
        System.err.println();
        editor.statusError(typefs + " Error: partition size could not be found!");
        return;
      }
    } catch(Exception e){
      editor.statusError(e);
      return;
    }

    System.out.println("Start: 0x" + String.format("%x", spiStart));
    System.out.println("Size : 0x" + String.format("%x", spiSize));

    File tool = new File(platform.getFolder() + "/tools", mkspiffsCmd);
    if (!tool.exists() || !tool.isFile()) {
      tool = new File(platform.getFolder() + "/tools/mk" + typefs.toLowerCase(), mkspiffsCmd);
      if (!tool.exists()) {
        tool = new File(PreferencesData.get("runtime.tools.mk" + typefs.toLowerCase() + ".path"), mkspiffsCmd);
        if (!tool.exists()) {
            System.err.println();
            editor.statusError(typefs + " Error: mk" + typefs.toLowerCase() + "not found!");
            return;
        }
      }
    }
  System.out.println("mk" + typefs.toLowerCase() + " : " + tool.getAbsolutePath());
  System.out.println();

    //make sure the serial port or IP is defined
    if (serialPort == null || serialPort.isEmpty()) {
      System.err.println();
      editor.statusError(typefs + " Error: serial port not defined!");
      return;
    }

    //find espota if IP else find esptool
    if(serialPort.split("\\.").length == 4){
      isNetwork = true;
      espota = new File(platform.getFolder()+"/tools", espotaCmd);
      if(!espota.exists() || !espota.isFile()){
    espota = new File(platform.getFolder()+"/tools", "espota.py");   //fall-back to .py
    if(!espota.exists() || !espota.isFile()){
      System.err.println();
      editor.statusError(typefs + " Error: espota not found!");
      return;
    }
      }
    System.out.println("espota : "+espota.getAbsolutePath());
      System.out.println();
    } else {
      String esptoolCmd = "esptool"+toolExtension;
      esptool = new File(platform.getFolder()+"/tools", esptoolCmd);
      if(!esptool.exists() || !esptool.isFile()){
        esptool = new File(platform.getFolder()+"/tools/esptool_py", esptoolCmd);
        if(!esptool.exists() || !esptool.isFile()){
            esptool = new File(platform.getFolder()+"/tools/esptool", esptoolCmd);
            if(!esptool.exists() || !esptool.isFile()){
              esptool = new File(PreferencesData.get("runtime.tools.esptool_py.path"), esptoolCmd);
              if(!esptool.exists() || !esptool.isFile()){
                esptool = new File(PreferencesData.get("runtime.tools.esptool.path"), esptoolCmd);
                if(!esptool.exists() || !esptool.isFile()){
                  System.err.println();
                  editor.statusError("Error: esptool not found!");
                  return;
                }
              }
            }
        }
      }
      System.out.println("esptool : "+esptool.getAbsolutePath());
      System.out.println();
    }

    //load a list of all files
    int fileCount = 0;
    File dataFolder = new File(editor.getSketch().getFolder(), "data");
    if (!dataFolder.exists()) {
        dataFolder.mkdirs();
    }
    if(dataFolder.exists() && dataFolder.isDirectory()){
      File[] files = dataFolder.listFiles();
      if(files.length > 0){
        for(File file : files){
          if((file.isDirectory() || file.isFile()) && !file.getName().startsWith(".")) fileCount++;
        }
      }
    }

    String dataPath = dataFolder.getAbsolutePath();
    String toolPath = tool.getAbsolutePath();
    String sketchName = editor.getSketch().getName();
    String imagePath = getBuildFolderPath(editor.getSketch()) + "/" + sketchName + "." + typefs.toLowerCase() + ".bin";
    String uploadSpeed = BaseNoGui.getBoardPreferences().get("upload.speed");

    Object[] options = { "Yes", "No" };
    String title = typefs + " Create";
    String message = "No files have been found in your data folder!\nAre you sure you want to create an empty " + typefs + " image?";

    if(fileCount == 0 && JOptionPane.showOptionDialog(editor, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]) != JOptionPane.YES_OPTION){
      System.err.println();
      editor.statusError(typefs + " Warning: mktool canceled!");
      return;
    }

    editor.statusNotice(typefs + " Creating Image...");
    System.out.println("[" + typefs + "] data   : "+dataPath);
    System.out.println("[" + typefs + "] offset : "+spiOffset);
    System.out.println("[" + typefs + "] start  : "+spiStart);
    System.out.println("[" + typefs + "] size   : "+(spiSize/1024));
    if (typefs != "FatFS") {
        System.out.println("[" + typefs + "] page   : "+spiPage);
        System.out.println("[" + typefs + "] block  : "+spiBlock);
    }

    try {
      if (typefs == "FatFS") {
        if(listenOnProcess(new String[]{toolPath, "-c", dataPath, "-s", spiSize+"", imagePath}) != 0){
          System.err.println();
          editor.statusError(typefs + " Create Failed!");
          return;
        }
      } else {
        if(listenOnProcess(new String[]{toolPath, "-c", dataPath, "-p", spiPage+"", "-b", spiBlock+"", "-s", spiSize+"", imagePath}) != 0){
          System.err.println();
          editor.statusError(typefs + " Create Failed!");
          return;
        }
      }

    } catch (Exception e){
      editor.statusError(e);
      editor.statusError(typefs + " Create Failed!");
      return;
    }

    editor.statusNotice(typefs + " Uploading Image...");
    System.out.println("[" + typefs + "] upload : "+imagePath);

    if(isNetwork){
      System.out.println("[" + typefs + "] IP     : "+serialPort);
    System.out.println("Running: " + espota.getAbsolutePath() + " -i " + serialPort + " -p 3232 -s -f " + imagePath);
    System.out.println();
      if(espota.getAbsolutePath().endsWith(".py"))
        sysExec(new String[]{pythonCmd, espota.getAbsolutePath(), "-i", serialPort, "-p", "3232", "-s", "-f", imagePath}); // other flags , "-d", "-r", "-t", "50"
      else
        sysExec(new String[]{espota.getAbsolutePath(), "-i", serialPort, "-p", "3232", "-s", "-f", imagePath});
    } else {
      String flashMode = BaseNoGui.getBoardPreferences().get("build.flash_mode");
      String flashFreq = BaseNoGui.getBoardPreferences().get("build.flash_freq");
      String boardName = BaseNoGui.getBoardPreferences().get("name");
      System.out.println("[" + typefs + "] address: "+spiStart);
      System.out.println("[" + typefs + "] port   : "+serialPort);
      System.out.println("[" + typefs + "] speed  : "+uploadSpeed);
      System.out.println("[" + typefs + "] name   : "+boardName);
      System.out.println("[" + typefs + "] chip   : "+chip);
      System.out.println("[" + typefs + "] mode   : "+flashMode);
      System.out.println("[" + typefs + "] freq   : "+flashFreq);
      System.out.println();
      // change after "write_flash" "-z" to "-u" (--no_compress) below to build file for esp32fs_no_compress.zip
      if(esptool.getAbsolutePath().endsWith(".py"))
        sysExec(new String[]{pythonCmd, esptool.getAbsolutePath(), "--chip", chip, "--baud", uploadSpeed, "--port", serialPort, "--before", "default_reset", "--after", "hard_reset", "write_flash", "-z", "--flash_mode", flashMode, "--flash_freq", flashFreq, "--flash_size", "detect", ""+spiStart, imagePath});
      else
        sysExec(new String[]{esptool.getAbsolutePath(), "--chip", chip, "--baud", uploadSpeed, "--port", serialPort, "--before", "default_reset", "--after", "hard_reset", "write_flash", "-z", "--flash_mode", flashMode, "--flash_freq", flashFreq, "--flash_size", "detect", ""+spiStart, imagePath});
    }
  }


  private void eraseFlash(){
    String chip = getChip();
    System.out.println("Chip : " + chip);
    if(!PreferencesData.get("target_platform").contains("esp32")){
      System.err.println();
      editor.statusError(typefs + " Not Supported on "+PreferencesData.get("target_platform"));
      return;
    }

    TargetPlatform platform = BaseNoGui.getTargetPlatform();

    String toolExtension = ".py";
    if(PreferencesData.get("runtime.os").contentEquals("windows")) {
      toolExtension = ".exe";
    } else if(PreferencesData.get("runtime.os").contentEquals("macosx")) {
      toolExtension = "";
    }

    String pythonCmd;
    if(PreferencesData.get("runtime.os").contentEquals("windows"))
        pythonCmd = "python.exe";
    else
        pythonCmd = "python";

    Boolean isNetwork = false;

    File esptool = new File(platform.getFolder()+"/tools");
    String serialPort = PreferencesData.get("serial.port");

    //make sure the serial port or IP is defined
    if (serialPort == null || serialPort.isEmpty()) {
      System.err.println();
      editor.statusError(typefs + " Error: serial port not defined!");
      return;
    }

    //find port
    if(serialPort.split("\\.").length == 4){
      isNetwork = true;
    } else {
      String esptoolCmd = "esptool"+toolExtension;
      esptool = new File(platform.getFolder()+"/tools", esptoolCmd);
      if(!esptool.exists() || !esptool.isFile()){
        esptool = new File(platform.getFolder()+"/tools/esptool_py", esptoolCmd);
        if(!esptool.exists() || !esptool.isFile()){
            esptool = new File(platform.getFolder()+"/tools/esptool", esptoolCmd);
            if(!esptool.exists() || !esptool.isFile()){
              esptool = new File(PreferencesData.get("runtime.tools.esptool_py.path"), esptoolCmd);
              if(!esptool.exists() || !esptool.isFile()){
                esptool = new File(PreferencesData.get("runtime.tools.esptool.path"), esptoolCmd);
                if(!esptool.exists() || !esptool.isFile()){
                  System.err.println();
                  editor.statusError("Error: esptool not found!");
                  return;
                }
              }
            }
        }
      }
    System.out.println("esptool : "+esptool.getAbsolutePath());
      System.out.println();
    }

    Object[] options = { "Yes", "No" };
    String title = "Erase All Flash";
    String message = "Are you sure?";

    if(JOptionPane.showOptionDialog(editor, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]) != JOptionPane.YES_OPTION){
      System.err.println();
      editor.statusError("Warning: Erase All Flash canceled!");
      return;
    }

    editor.statusNotice("Erasing all Flash started...");
    System.out.println("Erasing all Flash started...");

    if(isNetwork){
      System.out.println("Cannot be done through OTA, IP     : "+serialPort);
      System.out.println();
    } else {
      System.out.println("Port: "+serialPort);
      System.out.println();
      if(esptool.getAbsolutePath().endsWith(".py"))
        sysExec(new String[]{pythonCmd, esptool.getAbsolutePath(), "--chip", chip, "--port", serialPort, "--before", "default_reset", "--after", "hard_reset", "erase_flash"});
      else
        sysExec(new String[]{esptool.getAbsolutePath(), "--chip", chip, "--port", serialPort, "--before", "default_reset", "--after", "hard_reset", "erase_flash"});
    }
  }

  private String getChip(){
    return BaseNoGui.getBoardPreferences().get("build.mcu");
  }

  public void run() {
  String sketchName = editor.getSketch().getName();
    Object[] options = { "LittleFS", "SPIFFS", "FatFS", "!Erase Flash!" };
    typefs = (String)JOptionPane.showInputDialog(editor,
                                              "Select FS for " + sketchName +
                                              " /data folder",
                                              "Filesystem",
                                              JOptionPane.PLAIN_MESSAGE,
                                              null,
                                              options,
                                              "LittleFS");
    if ((typefs != null) && (typefs.length() > 0)) {
        if (typefs == "!Erase Flash!") {
            eraseFlash();
        } else {
            createAndUpload();
        }
    } else {
        System.out.println("Tool Exit.");
      return;
    }

  }
}
