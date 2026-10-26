package slooth.peripheral.ui;

import slooth.peripheral.script.ScriptPaths;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Local pre-generation snapshots; no provider call or file replacement occurs here. */
public final class BuildDraftSafety {
    private BuildDraftSafety() {}
    public static Path snapshot(String script) throws IOException {
        final Path source;
        try { source=ScriptPaths.resolve(script); }
        catch(IllegalArgumentException e){throw new IOException("Invalid script path",e);}
        if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("The selected script is not a regular file");
        long size=Files.size(source);
        if(size==0)return null;
        if(size>16L*1024*1024)throw new IOException("The script is too large for a safe pre-generation snapshot");
        Path root=Path.of("config","peripheral","build_backups").toAbsolutePath().normalize();
        rejectLinks(root);
        Files.createDirectories(root);ownerOnly(root,true);
        String tag;
        try {tag=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8))).substring(0,20);}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException("SHA-256 is unavailable",e);}
        Path folder=root.resolve(tag);rejectLinks(folder);Files.createDirectories(folder);ownerOnly(folder,true);
        String base=source.getFileName().toString().replaceAll("[^A-Za-z0-9._-]","_");
        if(base.length()>80)base=base.substring(base.length()-80);
        String stamp=Instant.now().toString().replace(':','-');
        Path target=folder.resolve(stamp+"-"+UUID.randomUUID().toString().substring(0,8)+"-"+base+".bak");
        byte[] bytes=Files.readAllBytes(source);
        if(bytes.length>16*1024*1024)throw new IOException("The script grew beyond the snapshot limit");
        try {
            if(Files.getFileStore(folder).supportsFileAttributeView("posix"))Files.write(Files.createFile(target,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))),bytes,StandardOpenOption.WRITE);
            else Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        }catch(IOException e){throw new IOException("Could not back up the current script; generation was not started",e);}
        return target;
    }
    private static void rejectLinks(Path path)throws IOException{
        for(Path part=path;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IOException("Backup directory must not contain symbolic links");
    }
    private static void ownerOnly(Path path,boolean directory)throws IOException{
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
    }
}
