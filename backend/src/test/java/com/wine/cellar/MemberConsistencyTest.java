package com.wine.cellar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import com.wine.cellar.common.BizException;
import com.wine.cellar.entity.Member;
import com.wine.cellar.entity.SaleOut;
import com.wine.cellar.service.MemberService;
import com.wine.cellar.service.SaleOutService;

/**
 * 会员档案一致性：等级始终由累计积分按同一阈值唯一推导。
 * 覆盖：阈值升级/降级、越级手填 level 被拒、只改联系方式不动积分、
 * 并发资料编辑与积分变动互不覆盖（过期提交被拒、保留最新积分）、退窖扣回积分后等级随之降级。
 */
@SpringBootTest
@DirtiesContext
class MemberConsistencyTest {

    @Autowired MemberService memberService;
    @Autowired SaleOutService saleService;

    private MemberService.MemberInput input(String phone, String name, Integer points) {
        MemberService.MemberInput in = new MemberService.MemberInput();
        in.phone = phone;
        in.name = name;
        in.points = points;
        return in;
    }

    private Member createAt(String phone, int points) {
        return memberService.create(input(phone, "测试会员", points));
    }

    /** 阈值升级与降级：2000 银卡、5000 黑卡，回落时必须同步降级，等级只能由积分决定 */
    @Test
    void levelAlwaysFollowsPointsThresholdsUpAndDown() {
        Member m = createAt("13700000001", 1900);
        assertEquals("普通", m.level);

        MemberService.MemberInput toSilver = input(m.phone, m.name, 2000);
        toSilver.version = m.version;
        m = memberService.update(m.id, toSilver);
        assertEquals(2000, m.points);
        assertEquals("银卡", m.level);
        assertEquals(1L, m.version, "每次保存版本号递增");

        MemberService.MemberInput toBlack = input(m.phone, m.name, 5000);
        toBlack.version = m.version;
        m = memberService.update(m.id, toBlack);
        assertEquals("黑卡", m.level);

        // 降级：积分回落到阈值以下，等级必须跟着掉（旧逻辑只升不降的隐患）
        MemberService.MemberInput down = input(m.phone, m.name, 1999);
        down.version = m.version;
        m = memberService.update(m.id, down);
        assertEquals(1999, m.points);
        assertEquals("普通", m.level);

        // 列表等级筛选与积分一致（刷新后仍然成立）
        final long id = m.id;
        assertTrue(memberService.list("银卡").stream().noneMatch(x -> x.id == id));
        MemberService.MemberInput back = input(m.phone, m.name, 2600);
        back.version = m.version;
        memberService.update(id, back);
        assertTrue(memberService.list("银卡").stream().anyMatch(x -> x.id == id));
        assertTrue(memberService.list("黑卡").stream().noneMatch(x -> x.id == id));
    }

    /** 越级手填：无论新增还是编辑，level 出现在入参里就拒绝，绝不能只靠前端隐藏下拉框 */
    @Test
    void handFilledLevelIsRejectedOnEveryEntry() {
        // 新增时手填黑卡（积分只有 0）→ 拒绝
        MemberService.MemberInput cheatCreate = input("13700000002", "作弊会员", 0);
        cheatCreate.setLevel("黑卡");
        BizException ex = assertThrows(BizException.class, () -> memberService.create(cheatCreate));
        assertTrue(ex.getMessage().contains("积分") || ex.getMessage().contains("等级"), ex.getMessage());

        // 编辑时只提交 level 也拒绝，原等级保持普通
        Member m = createAt("13700000002", 100);
        assertEquals("普通", m.level);
        MemberService.MemberInput cheatUpdate = new MemberService.MemberInput();
        cheatUpdate.setLevel("黑卡");
        cheatUpdate.version = m.version;
        assertThrows(BizException.class, () -> memberService.update(m.id, cheatUpdate));
        Member reread = memberService.get(m.id);
        assertEquals("普通", reread.level, "手填等级不得落库");
        assertEquals(100, reread.points);

        // 即使手填等级与积分阈值恰好吻合也不接受该字段（等级没有可写通道）
        Member m2 = createAt("13700000003", 0);
        MemberService.MemberInput matching = new MemberService.MemberInput();
        matching.setLevel("银卡");
        matching.version = m2.version;
        assertThrows(BizException.class, () -> memberService.update(m2.id, matching));
        assertEquals("普通", memberService.get(m2.id).level);

        // 正道：调积分到阈值，等级自动成为黑卡
        MemberService.MemberInput legit = input(m2.phone, m2.name, 6000);
        legit.version = m2.version;
        assertEquals("黑卡", memberService.update(m2.id, legit).level);
    }

    /** 只改手机号/昵称：积分与等级必须原封不动，版本号正常推进 */
    @Test
    void contactOnlyEditNeverTouchesPointsOrLevel() {
        Member m = createAt("13700000004", 2600);
        assertEquals("银卡", m.level);

        MemberService.MemberInput profile = new MemberService.MemberInput();
        profile.phone = "13700000099";
        profile.name = "改个昵称";
        profile.version = m.version;
        Member saved = memberService.update(m.id, profile);

        assertEquals(2600, saved.points, "只改联系方式不得改动积分");
        assertEquals("银卡", saved.level, "只改联系方式不得改动等级");
        assertEquals("改个昵称", saved.name);
        assertEquals("13700000099", saved.phone);
    }

    /** 积分调整必须非负 */
    @Test
    void negativePointsAreRejected() {
        Member m = createAt("13700000005", 100);
        MemberService.MemberInput neg = input(m.phone, m.name, -1);
        neg.version = m.version;
        assertThrows(BizException.class, () -> memberService.update(m.id, neg));
        assertEquals(100, memberService.get(m.id).points);

        assertThrows(BizException.class, () -> memberService.addPoints(m.id, -101));
        assertEquals(100, memberService.get(m.id).points, "扣成负数时积分保持原值");
    }

    /**
     * 过期资料提交必须拒绝并保留最新积分：A 打开编辑（持有旧版本号），期间 B 完成积分变动，
     * A 再保存联系方式时必须被告知冲突、刷新；刷新后带新版本号可正常保存，积分始终不被覆盖。
     */
    @Test
    void staleProfileEditIsRejectedAndLatestPointsSurvive() {
        Member m = createAt("13700000006", 1900);
        Long snapshotVersion = m.version;   // 前端打开编辑对话框时拿到的版本号

        // 另一入口（销售出窖联动）先完成积分变动并提交：1900 + 3100 = 5000 黑卡
        assertEquals("黑卡", memberService.addPoints(m.id, 3100).level);

        // A 拿着过期版本号只改手机号（不带 points）→ 拒绝，旧表单不得覆盖最新积分
        MemberService.MemberInput stale = new MemberService.MemberInput();
        stale.phone = "13700000088";
        stale.version = snapshotVersion;
        BizException ex = assertThrows(BizException.class, () -> memberService.update(m.id, stale));
        assertTrue(ex.getMessage().contains("刷新"), ex.getMessage());

        Member latest = memberService.get(m.id);
        assertEquals(5000, latest.points, "最新积分必须保留，不得被旧表单覆盖");
        assertEquals("黑卡", latest.level, "等级与最新积分一致");
        assertEquals("13700000006", latest.phone, "被拒的联系方式修改不得落库");

        // A 刷新后携带新版本号重试，联系方式保存成功，积分与等级依旧不动
        MemberService.MemberInput fresh = new MemberService.MemberInput();
        fresh.phone = "13700000088";
        fresh.version = latest.version;
        Member saved = memberService.update(m.id, fresh);
        assertEquals("13700000088", saved.phone);
        assertEquals(5000, saved.points);
        assertEquals("黑卡", saved.level);
    }

    /**
     * 真实并发压测：每轮一个积分增量与一个带「打开时」版本号的资料编辑同时发起。
     * 合法串行序有两种（编辑先成功则积分随后提交；积分先提交则编辑过期被拒），
     * 但无论实际先后，积分更新一次都不能丢，等级最终必须与积分一致。
     */
    @Test
    void concurrentPointsChangeAndProfileEditNeverLosePoints() throws Exception {
        Member m = createAt("13700000090", 1900);

        int rounds = 5;
        int deltaPerRound = 700;
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            Member snapshot = memberService.get(m.id);   // 编辑对话框打开时的快照
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> editFailure = new AtomicReference<>();
            pool.submit(() -> {
                try { start.await(); memberService.addPoints(m.id, deltaPerRound); }
                catch (Throwable t) { throw new RuntimeException("积分侧不应失败", t); }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    MemberService.MemberInput profile = new MemberService.MemberInput();
                    profile.name = "并发改名" + round;
                    profile.version = snapshot.version;
                    memberService.update(m.id, profile);
                } catch (Throwable t) {
                    editFailure.set(t);
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            if (editFailure.get() != null) {
                // 编辑侧唯一允许的失败原因：版本过期（提示用户刷新）
                assertTrue(editFailure.get().getMessage().contains("刷新")
                        || editFailure.get().getMessage().contains("过期"), editFailure.get().getMessage());
            }
        }

        Member finalMember = memberService.get(m.id);
        assertEquals(1900 + rounds * deltaPerRound, finalMember.points, "并发下积分更新一次都不能丢");
        assertEquals("黑卡", finalMember.level, "5400 分应为黑卡，等级与积分一致");
    }

    /** 退窖扣回本单积分后，等级必须随积分阈值降级（黑卡也能掉回银卡/普通） */
    @Test
    void refundClawsPointsBackAndDowngradesLevel() {
        // 4000 分银卡会员，出窖 20 瓶 ×100 元 = 2000 积分 → 6000 黑卡
        Member m = createAt("13700000007", 4000);
        assertEquals("银卡", m.level);

        SaleOutService.SaleCreateInput in = new SaleOutService.SaleCreateInput();
        in.memberId = m.id;
        SaleOutService.SaleItemInput it = new SaleOutService.SaleItemInput();
        it.wineId = 1L;
        it.qty = 20;
        in.items = List.of(it);
        SaleOut sale = saleService.create(in);
        assertEquals(2000, sale.pointsEarned);
        Member afterSale = memberService.get(m.id);
        assertEquals(6000, afterSale.points);
        assertEquals("黑卡", afterSale.level);

        // 退窖：积分精确扣回，等级随之降回银卡
        SaleOut refunded = saleService.refund(sale.id);
        assertEquals("已退窖", refunded.status);
        Member afterRefund = memberService.get(m.id);
        assertEquals(4000, afterRefund.points, "退窖必须扣回本单积分");
        assertEquals("银卡", afterRefund.level, "退窖扣回后必须按同一阈值降级");

        // 再验一次跨两级回落：1900 分普通会员出窖 40 瓶 → 5900 黑卡，退窖后回普通
        Member plain = createAt("13700000008", 1900);
        SaleOutService.SaleCreateInput in2 = new SaleOutService.SaleCreateInput();
        in2.memberId = plain.id;
        SaleOutService.SaleItemInput it2 = new SaleOutService.SaleItemInput();
        it2.wineId = 1L;
        it2.qty = 40;
        in2.items = List.of(it2);
        SaleOut sale2 = saleService.create(in2);
        assertEquals("黑卡", memberService.get(plain.id).level);
        saleService.refund(sale2.id);
        Member backToPlain = memberService.get(plain.id);
        assertEquals(1900, backToPlain.points);
        assertEquals("普通", backToPlain.level);
    }
}
