/** JavaClaw 独立 Knowledge 解析 Worker；PDFBox 与 POI 不进入 App Server 模块边界。 */
module com.javaclaw.knowledge.worker {
    requires com.javaclaw.api;
    requires com.javaclaw.builtin.contracts;
    requires com.javaclaw.protocol;
    requires org.apache.pdfbox;
    requires org.apache.poi.ooxml;
}
