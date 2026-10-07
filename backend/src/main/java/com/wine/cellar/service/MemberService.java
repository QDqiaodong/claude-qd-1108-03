package com.wine.cellar.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.wine.cellar.common.BizException;
import com.wine.cellar.common.Dict;
import com.wine.cellar.entity.Member;
import com.wine.cellar.repository.MemberRepository;

@Service
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    // ---------------- 查询 ----------------
    public List<Member> list(String level) {
        List<Member> all = memberRepository.findAll();
        if (level != null && !level.isBlank()) {
            all = all.stream().filter(m -> level.equals(m.level)).toList();
        }
        return all;
    }

    public Member get(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new BizException("会员不存在"));
    }

    /** 行锁读取会员（出窖/退窖联动积分时使用），调用方须在事务内 */
    public Member getLocked(Long id) {
        return memberRepository.findLockingById(id)
                .orElseThrow(() -> new BizException("会员不存在"));
    }

    // ---------------- 新建 ----------------
    public static class MemberInput {
        public String phone;
        public String name;
        public String level;
        public Integer points;
    }

    public Member create(MemberInput input) {
        if (input.phone == null || input.phone.isBlank()) {
            throw new BizException("手机号不能为空");
        }
        if (memberRepository.existsByPhone(input.phone)) {
            throw new BizException("手机号已存在，不可重复");
        }
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("昵称不能为空");
        }
        if (input.points == null || input.points < 0) {
            throw new BizException("积分不能为负");
        }

        Member m = new Member();
        m.phone = input.phone;
        m.name = input.name;
        m.level = (input.level == null || input.level.isBlank()) ? "普通" : input.level;
        if (!Dict.isValidMemberLevel(m.level)) {
            throw new BizException("等级必须是：普通/银卡/黑卡 之一");
        }
        m.points = input.points;
        return memberRepository.save(m);
    }

    // ---------------- 更新（部分字段） ----------------
    public Member update(Long id, MemberInput input) {
        Member m = get(id);
        if (input.phone != null) {
            if (input.phone.isBlank()) {
                throw new BizException("手机号不能为空");
            }
            if (!input.phone.equals(m.phone) && memberRepository.existsByPhoneAndIdNot(input.phone, id)) {
                throw new BizException("手机号已存在，不可重复");
            }
            m.phone = input.phone;
        }
        if (input.name != null) {
            if (input.name.isBlank()) {
                throw new BizException("昵称不能为空");
            }
            m.name = input.name;
        }
        if (input.level != null) {
            if (!Dict.isValidMemberLevel(input.level)) {
                throw new BizException("等级必须是：普通/银卡/黑卡 之一");
            }
            m.level = input.level;
        }
        if (input.points != null) {
            if (input.points < 0) {
                throw new BizException("积分不能为负");
            }
            m.points = input.points;
            // 积分变化后自动复核等级
            m.level = Dict.decideMemberLevel(m.points);
        }
        return memberRepository.save(m);
    }

    // ---------------- 动作：累加积分（并自动升级等级） ----------------
    /** 行锁内调整积分（delta 可为负），防止并发出窖/退窖丢失更新；积分不得为负 */
    @org.springframework.transaction.annotation.Transactional
    public Member addPoints(Long id, int delta) {
        Member m = getLocked(id);
        int next = m.points + delta;
        if (next < 0) {
            throw new BizException("会员「" + m.name + "」积分扣减后将为负，退窖失败（当前积分 " + m.points + "，应扣 " + (-delta) + "）");
        }
        m.points = next;
        m.level = Dict.decideMemberLevel(m.points);
        return memberRepository.save(m);
    }
}
