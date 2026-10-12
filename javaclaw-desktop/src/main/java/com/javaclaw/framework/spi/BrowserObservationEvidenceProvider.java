package com.javaclaw.framework.spi;

import java.util.Map;

/** 由精确内置浏览器实现提供本次读取的正文证明；扩展实现不能取得可信身份。 */
public interface BrowserObservationEvidenceProvider {
    /** 消费当前执行线程为指定工具记录的证明，不能复用上一次读取的数据。 */
    Map<String, String> consumeBrowserObservationEvidence(String tool);
}
