package io.github.soonsoonyy.processdiary.data

import com.sun.jna.Native
import com.sun.jna.platform.mac.SystemB

/**
 * macOS 프로세스 정보를 OSHI 대신 직접 읽는다.
 * OSHI 6.4.x는 작업 폴더 경로를 US-ASCII로 디코딩해 한글·일본어 등 ASCII가 아닌 글자가 '�'로 깨진다.
 * 커널이 돌려주는 경로 바이트는 UTF-8이므로 같은 시스템 호출(proc_pidinfo)로 읽어 UTF-8로 디코딩한다.
 * (SystemB는 IDE에 포함된 jna-platform에 있음)
 */
object MacProc {
    /** 작업 폴더 (읽지 못하면 null: 다른 사용자의 프로세스 등) */
    fun cwd(pid: Int): String? = try {
        val info = SystemB.VnodePathInfo()
        val size = info.size()
        if (SystemB.INSTANCE.proc_pidinfo(pid, SystemB.PROC_PIDVNODEPATHINFO, 0, info, size) < size) null
        else Native.toString(info.pvi_cdir.vip_path, Charsets.UTF_8).ifEmpty { null }
    } catch (_: Throwable) {
        null
    }
}
