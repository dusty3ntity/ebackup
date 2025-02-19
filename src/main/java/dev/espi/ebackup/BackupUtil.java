package dev.espi.ebackup;

import com.jcraft.jsch.*;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;

import java.io.*;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/*
   Copyright 2020 EspiDev

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

public class BackupUtil {

    // delete old backups (when limit reached)
    private static void checkMaxBackups() {
        if (eBackup.getPlugin().maxBackups <= 0) return;

        int backups = 0;
        SortedMap<Long, File> m = new TreeMap<>(); // oldest files to newest

        for (File f : eBackup.getPlugin().backupPath.listFiles()) {
            if (f.getName().endsWith(".zip")) {
                backups++;
                m.put(f.lastModified(), f);
            }
        }

        // delete old backups
        while (backups-- >= eBackup.getPlugin().maxBackups) {
            m.get(m.firstKey()).delete();
            m.remove(m.firstKey());
        }
    }

    // actually do the backup
    // run async please
    public static void doBackup(boolean uploadToServer, boolean isScheduled) {
        List<File> tempIgnore = new ArrayList<>();
        if (isScheduled) {
            Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.GREEN + "eBackup" + ChatColor.GRAY + "] " + ChatColor.GOLD + "Starting scheduled backup...");
        } else {
            eBackup.getPlugin().getLogger().info("Starting backup...");
        }

        // do not backup when plugin is disabled
        if (!eBackup.getPlugin().isEnabled()) {
            if (isScheduled) {
                Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.GREEN + "eBackup" + ChatColor.GRAY + "] " + ChatColor.GOLD + "Unable to start a backup, the plugin is disabled by the server!");
            } else {
                eBackup.getPlugin().getLogger().warning("Unable to start a backup, the plugin is disabled by the server!");
            }
            return;
        }

        // prevent other processes from backing up at the same time
        eBackup.getPlugin().isInBackup.set(true);

        File currentWorkingDirectory = new File(Paths.get(".").toAbsolutePath().normalize().toString());

        try {
            // find plugin data to ignore
            for (File f : new File("plugins").listFiles()) {
                if ((!eBackup.getPlugin().backupPluginJars && f.getName().endsWith(".jar")) || (!eBackup.getPlugin().backupPluginConfs && f.isDirectory())) {
                    tempIgnore.add(f);
                    eBackup.getPlugin().ignoredFiles.add(f);
                }
            }

            // delete old backups
            checkMaxBackups();

            // zip
            SimpleDateFormat formatter = new SimpleDateFormat(eBackup.getPlugin().backupDateFormat);
            String fileName = eBackup.getPlugin().backupFormat.replace("{DATE}", formatter.format(new Date()));
            FileOutputStream fos = new FileOutputStream(eBackup.getPlugin().backupPath + "/" + fileName + ".zip");
            ZipOutputStream zipOut = new ZipOutputStream(fos);

            // set zip compression level
            zipOut.setLevel(eBackup.getPlugin().compressionLevel);

            Set<String> visitedFiles = new HashSet<>();

            // backup worlds first
            for (World w : Bukkit.getWorlds()) {
                File worldFolder = w.getWorldFolder();

                String worldPath = Paths.get(currentWorkingDirectory.toURI()).relativize(Paths.get(worldFolder.toURI())).toString();
                if (worldPath.endsWith("/.")) {// 1.16 world folders end with /. for some reason
                    worldPath = worldPath.substring(0, worldPath.length() - 2);
                    worldFolder = new File(worldPath);
                }

                // check if world is in ignored list
                boolean skip = false;
                for (File f : eBackup.getPlugin().ignoredFiles) {
                    if (f.getCanonicalPath().equals(worldFolder.getCanonicalPath())) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;

                // manually trigger world save (needs to be run sync)
                AtomicBoolean saved = new AtomicBoolean(false);
                Bukkit.getScheduler().runTask(eBackup.getPlugin(), () -> {
                    w.save();
                    saved.set(true);
                });

                // wait until world save is finished
                while (!saved.get()) Thread.sleep(500);

                w.setAutoSave(false); // make sure autosave doesn't screw everything over

                eBackup.getPlugin().getLogger().info("Backing up world " + w.getName() + " " + worldPath + "...");
                zipFile(worldFolder, worldPath, zipOut, visitedFiles);

                w.setAutoSave(true);

                // ignore in dfs
                tempIgnore.add(worldFolder);
                eBackup.getPlugin().ignoredFiles.add(worldFolder);
            }

            // dfs all other files
            eBackup.getPlugin().getLogger().info("Backing up other files...");
            zipFile(currentWorkingDirectory, "", zipOut, visitedFiles);
            zipOut.close();
            fos.close();

            // upload to ftp/sftp
            if (uploadToServer && eBackup.getPlugin().ftpEnable) {
                uploadTask(eBackup.getPlugin().backupPath + "/" + fileName + ".zip", false);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            for (World w : Bukkit.getWorlds()) {
                w.setAutoSave(true);
            }
            // restore tempignore
            for (File f : tempIgnore) {
                eBackup.getPlugin().ignoredFiles.remove(f);
            }

            // unlock
            eBackup.getPlugin().isInBackup.set(false);
        }
        if (isScheduled) {
            Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.GREEN + "eBackup" + ChatColor.GRAY + "] " + ChatColor.GOLD + "Scheduled backup complete!");
        } else {
            eBackup.getPlugin().getLogger().info("Local backup complete!");
        }
    }

    public static void testUpload() {
        try {
            File temp = new File(eBackup.getPlugin().getDataFolder() + "/uploadtest.txt");
            temp.createNewFile();
            uploadTask(temp.toString(), true);
        } catch (Exception e) {
            e.printStackTrace();
            eBackup.getPlugin().getLogger().warning("Error creating temporary file.");
        }
    }

    private static void uploadTask(String fileName, boolean testing) {
        if (eBackup.getPlugin().isInUpload.get()) {
            eBackup.getPlugin().getLogger().warning("A upload was scheduled to happen now, but an upload was detected to be in progress. Skipping...");
            return;
        }

        boolean isSFTP = eBackup.getPlugin().ftpType.equals("sftp"), isFTP = eBackup.getPlugin().ftpType.equals("ftp");
        if (!isSFTP && !isFTP) {
            eBackup.getPlugin().getLogger().warning("Invalid upload type specified (only ftp/sftp accepted). Skipping upload...");
            return;
        }

        eBackup.getPlugin().getLogger().info(String.format("Starting upload of %s to %s server...", fileName, isSFTP ? "SFTP" : "FTP"));
        Bukkit.getScheduler().runTaskAsynchronously(eBackup.getPlugin(), () -> {
            try {
                eBackup.getPlugin().isInUpload.set(true);

                File f = new File(fileName);
                if (isSFTP) {
                    uploadSFTP(f, testing);
                } else {
                    uploadFTP(f, testing);
                }

                // delete testing file
                if (testing) {
                    f.delete();
                    eBackup.getPlugin().getLogger().info("Test upload successful!");
                } else {
                    eBackup.getPlugin().getLogger().info("Upload of " + fileName + " has succeeded!");
                }
            } catch (Exception e) {
                e.printStackTrace();
                eBackup.getPlugin().getLogger().info("Upload of " + fileName + " has failed.");
            } finally {
                eBackup.getPlugin().isInUpload.set(false);
            }
        });
    }

    private static void uploadSFTP(File f, boolean testing) throws JSchException, SftpException {
        JSch jsch = new JSch();

        // ssh key auth if enabled
        if (eBackup.getPlugin().useSftpKeyAuth) {
            if (eBackup.getPlugin().sftpPrivateKeyPassword.equals("")) {
                jsch.addIdentity(eBackup.getPlugin().sftpPrivateKeyPath);
            } else {
                jsch.addIdentity(eBackup.getPlugin().sftpPrivateKeyPath, eBackup.getPlugin().sftpPrivateKeyPassword);
            }
        }

        Session session = jsch.getSession(eBackup.getPlugin().ftpUser, eBackup.getPlugin().ftpHost, eBackup.getPlugin().ftpPort);
        // password auth if using password
        if (!eBackup.getPlugin().useSftpKeyAuth) {
            session.setPassword(eBackup.getPlugin().ftpPass);
        }
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();

        Channel channel = session.openChannel("sftp");
        channel.connect();
        ChannelSftp sftpChannel = (ChannelSftp) channel;
        sftpChannel.put(f.getAbsolutePath(), eBackup.getPlugin().ftpPath);

        if (testing) {
            // delete testing file
            sftpChannel.rm(eBackup.getPlugin().ftpPath + "/" + f.getName());
        }

        sftpChannel.exit();
        session.disconnect();

        if (!testing) {
            deleteAfterUpload(f);
        }
    }

    private static void uploadFTP(File f, boolean testing) throws IOException {
        FTPClient ftpClient = new FTPClient();
        ftpClient.setAutodetectUTF8(true);
        try (FileInputStream fio = new FileInputStream(f)) {
            ftpClient.setDataTimeout(180 * 1000);
            ftpClient.setConnectTimeout(180 * 1000);
            ftpClient.setDefaultTimeout(180 * 1000);
            ftpClient.setControlKeepAliveTimeout(60);

            ftpClient.connect(eBackup.getPlugin().ftpHost, eBackup.getPlugin().ftpPort);
            ftpClient.enterLocalPassiveMode();

            ftpClient.login(eBackup.getPlugin().ftpUser, eBackup.getPlugin().ftpPass);
            ftpClient.setUseEPSVwithIPv4(true);

            ftpClient.changeWorkingDirectory(eBackup.getPlugin().ftpPath);
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setBufferSize(1024 * 1024 * 16);

            if (ftpClient.storeFile(f.getName(), fio)) {
                if (testing) {
                    // delete testing file
                    ftpClient.deleteFile(f.getName());
                } else {
                    deleteAfterUpload(f);
                }
            } else {
                // ensure that an error message is printed if the file cannot be stored
                throw new IOException();
            }
        } finally {
            try {
                ftpClient.disconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void deleteAfterUpload(File f) {
        if (eBackup.getPlugin().deleteAfterUpload) {
            Bukkit.getScheduler().runTaskAsynchronously(eBackup.getPlugin(), () -> {
                if (f.delete()) {
                    eBackup.getPlugin().getLogger().info("Successfully deleted " + f.getName() + " after upload.");
                } else {
                    eBackup.getPlugin().getLogger().warning("Unable to delete " + f.getName() + " after upload.");
                }
            });
        }
    }

    // recursively compress files and directories
    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut, Set<String> visitedFiles) throws IOException {
        // ignore if this file has been visited before
        if (visitedFiles.contains(fileToZip.getCanonicalPath())) return;

        // return if it is ignored file
        for (File f : eBackup.getPlugin().ignoredFiles) {
            if (f.getCanonicalPath().equals(fileToZip.getCanonicalPath())) return;
        }

        // fix windows archivers not being able to see files because they don't support / (root) for zip files
        if (fileName.startsWith("/") || fileName.startsWith("\\")) {
            fileName = fileName.substring(1);
        }
        // make sure there won't be a "." folder
        if (fileName.startsWith("./") || fileName.startsWith(".\\")) {
            fileName = fileName.substring(2);
        }
        // truncate \. on windows (from the end of folder names)
        if (fileName.endsWith("/.") || fileName.endsWith("\\.")) {
            fileName = fileName.substring(0, fileName.length()-2);
        }

        visitedFiles.add(fileToZip.getCanonicalPath());

        if (fileToZip.isDirectory()) { // if it's a directory, recursively search
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
            }
            zipOut.closeEntry();
            File[] children = fileToZip.listFiles();
            for (File childFile : children) {
                zipFile(childFile, fileName + "/" + childFile.getName(), zipOut, visitedFiles);
            }
        } else { // if it's a file, store
            try {
                FileInputStream fis = new FileInputStream(fileToZip);
                ZipEntry zipEntry = new ZipEntry(fileName);
                zipOut.putNextEntry(zipEntry);
                byte[] bytes = new byte[1024];
                int length;
                while ((length = fis.read(bytes)) >= 0) {
                    zipOut.write(bytes, 0, length);
                }
                fis.close();
            } catch (IOException e) {
                eBackup.getPlugin().getLogger().warning("Error while backing up file " + fileName + ", backup will ignore this file: " + e.getMessage());
            }
        }
    }
}
