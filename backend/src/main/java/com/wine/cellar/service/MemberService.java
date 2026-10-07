package com.wine.cellar.service;

import java.util.List;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
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

    // ---------------- 入参 ----------------
    /**
     * 会员档案入参：没有 level 字段——等级只能由累计积分按阈值唯一推导。
     * 若请求体仍手工携带 level（旧前端/直接调接口），按「接口仍可写」拒绝，而非静默忽略后落库。
     */
    public static class MemberInput {
        public String phone;
        public String name;
        public Integer points;
        /** 编辑时携带的乐观锁版本号（取列表/详情返回的 version），过期提交会被拒绝 */
        public Long version;

        /** 等级不可提交：仅用于识别请求里是否手工携带了 level，值一律不采纳 */
        @JsonIgnore
        public boolean levelProvided;

        /** 等级是积分的派生只读结果：接口识别到该字段即拒绝，防止只隐藏前端下拉框却留下可写通道 */
        @JsonSetter("level")
        public void setLevel(String level) {
            this.levelProvided = true;
        }
    }

    private static final String LEVEL_READONLY_MSG =
            "会员等级由累计积分自动核定（普通 <" + Dict.SILVER_POINTS + " 分、银卡 "
                    + Dict.SILVER_POINTS + "-" + (Dict.BLACK_POINTS - 1) + " 分、黑卡 ≥"
                    + Dict.BLACK_POINTS + " 分），不能手工提交，请直接调整积分";

    // ---------------- 新建 ----------------
    @Transactional
    public Member create(MemberInput input) {
        if (input.levelProvided) {
            throw new BizException(LEVEL_READONLY_MSG);
        }
        if (input.phone == null || input.phone.isBlank()) {
            throw new BizException("手机号不能为空");
        }
        if (memberRepository.existsByPhone(input.phone)) {
            throw new BizException("手机号已存在，不可重复");
        }
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("昵称不能为空");
        }
        int points = (input.points == null) ? 0 : input.points;
        if (points < 0) {
            throw new BizException("积分不能为负");
        }

        Member m = new Member();
        m.phone = input.phone;
        m.name = input.name;
        m.points = points;
        // 等级始终由积分唯一决定，任何入口都不接受手填
        m.level = Dict.decideMemberLevel(points);
        return memberRepository.save(m);
    }

    // ---------------- 更新（部分字段） ----------------
    /**
     * 档案编辑与积分调整分两类字段：
     *   - phone/name：联系方式修改，出现才更新，绝不触碰积分与等级；
     *   - points：积分调整（字段出现才算），校验非负，并在同一保存结果里按阈值重算等级。
     * level 任何情况下都不接受提交。version 过期（期间发生过出窖/退窖/加减积分/他人编辑）一律拒绝，
     * 防止旧表单把最新积分覆盖丢失。
     */
    @Transactional
    public Member update(Long id, MemberInput input) {
        if (input.levelProvided) {
            throw new BizException(LEVEL_READONLY_MSG);
        }
        Member m = get(id);
        if (input.version != null && !input.version.equals(m.version)) {
            throw new BizException("会员「" + m.name + "」的资料已被其他操作更新"
                    + "（期间可能发生出窖、退窖或积分调整，最新积分 " + m.points + "、等级 " + m.level
                    + "），请刷新后重新提交");
        }

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
        if (input.points != null) {
            if (input.points < 0) {
                throw new BizException("积分不能为负");
            }
            m.points = input.points;
            // 积分与等级同一业务结果落库：阈值复核，允许升级也允许降级
            m.level = Dict.decideMemberLevel(m.points);
        }
        try {
            return memberRepository.saveAndFlush(m);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 读取后、提交前恰好被出窖/退窖/加减积分/他人编辑抢先提交：拒绝本次过期写入，最新积分保持不变
            throw new BizException("会员「" + m.name + "」的资料刚被其他操作更新，本次提交已过期（请勿覆盖最新积分），请刷新后重试");
        }
    }

    // ---------------- 动作：累加积分（并自动复核等级） ----------------
    /** 行锁内按 delta 调整积分（delta 可为负），防止并发出窖/退窖丢失更新；结果积分不得为负，等级随阈值重算 */
    @Transactional
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
