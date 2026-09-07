package com.javaclaw.nativehost.ffm;

import java.util.ArrayList;
import java.util.List;

/** 区分授权目录本身和子孙：受信任根不可被目标删除、改 owner 或改 DACL。 */
final class WindowsFileAclPolicy {
    private static final int DELETE = 0x00010000;
    private static final int WRITE_DAC = 0x00040000;
    private static final int WRITE_OWNER = 0x00080000;
    private static final int FILE_WRITE_DATA = 2;

    private WindowsFileAclPolicy() {}

    static List<Entry> entries(boolean directory, int allowed, int denied) {
        int inheritance = directory ? 3 : 0;
        boolean protectRoot = directory && (allowed & FILE_WRITE_DATA) != 0;
        List<Entry> result = new ArrayList<>();
        result.add(new Entry(protectRoot ? allowed & ~DELETE : allowed, 1, inheritance));
        if (denied != 0) {
            result.add(new Entry(denied, 3, inheritance));
        }
        if (protectRoot) {
            result.add(new Entry(DELETE | WRITE_DAC | WRITE_OWNER, 3, 0));
            if ((allowed & DELETE) != 0) {
                // INHERIT_ONLY_ACE(8) 使 DELETE 只对后代生效，不允许替换服务端选定的 cache/writeRoot。
                result.add(new Entry(DELETE, 1, inheritance | 8));
            }
        }
        return List.copyOf(result);
    }

    record Entry(int permissions, int mode, int inheritance) {}
}
