package com.opentermx.ssh;

public record SftpEntry(
        String name,
        boolean directory,
        boolean symlink,
        long size,
        long modifiedMillis,
        String permissions
) {
    public boolean isParent() {
        return "..".equals(name);
    }

    public boolean isCurrent() {
        return ".".equals(name);
    }
}