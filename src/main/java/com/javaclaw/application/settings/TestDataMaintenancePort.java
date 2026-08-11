package com.javaclaw.application.settings;

import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.Candidate;

import java.util.List;

/** 历史测试数据扫描与受限删除端口；实现必须在删除前重新验证每个候选。 */
public interface TestDataMaintenancePort {

    List<Candidate> scanDefaultLocations();

    int deleteConfirmed(List<Candidate> candidates);
}
