/** 单 Turn Thin Harness 与模型端口。 */
module com.javaclaw.agent.runtime {
    requires transitive com.javaclaw.api;
    requires org.slf4j;

    exports com.javaclaw.runtime;
}
