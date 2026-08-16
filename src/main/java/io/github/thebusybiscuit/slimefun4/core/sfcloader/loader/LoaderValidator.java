package io.github.thebusybiscuit.slimefun4.core.sfcloader.loader;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.*;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.util.*;

public class LoaderValidator {
    
    private final Gson gson = new Gson();

    public record ValidationResult(boolean valid, @Nullable LoaderManifest manifest, @Nonnull List<String> errors) {}

    @Nonnull
    public ValidationResult validate(@Nonnull Path loaderFile) {
        List<String> errors = new ArrayList<>();
        LoaderManifest manifest = null;
        
        if (!Files.exists(loaderFile)) {
            errors.add("File does not exist: " + loaderFile.toString());
            return new ValidationResult(false, null, errors);
        }
        
        try (ZipFile zipFile = new ZipFile(loaderFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            boolean hasManifest = false;
            
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    errors.add("ZIP-SLIP vulnerability detected in entry: " + name);
                    return new ValidationResult(false, null, errors);
                }
                
                if (name.equals("manifest.json")) {
                    hasManifest = true;
                    try (Reader reader = new InputStreamReader(zipFile.getInputStream(entry))) {
                        manifest = gson.fromJson(reader, LoaderManifest.class);
                    } catch (JsonSyntaxException e) {
                        errors.add("manifest.json could not be parsed as valid JSON.");
                    }
                }
            }
            
            if (!hasManifest) {
                errors.add("Missing manifest.json in the archive.");
            } else if (manifest != null) {
                if (manifest.getFormatVersion() != 1) {
                    errors.add("Unsupported format_version in manifest. Expected 1, got: " + manifest.getFormatVersion());
                }
            }
            
        } catch (Exception e) {
            errors.add("Invalid zip file or could not read contents: " + e.getMessage());
        }
        
        return new ValidationResult(errors.isEmpty(), manifest, errors);
    }
}
