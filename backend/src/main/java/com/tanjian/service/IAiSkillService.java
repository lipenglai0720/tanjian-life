package com.tanjian.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tanjian.dto.AiSkillRunRequest;
import com.tanjian.dto.Result;
import com.tanjian.entity.AiSkill;

public interface IAiSkillService extends IService<AiSkill> {

    Result queryEnabledSkills();

    Result querySkillByCode(String code);

    Result runSkill(String code, AiSkillRunRequest request);

    Result queryMyRuns(String skillCode);
}
