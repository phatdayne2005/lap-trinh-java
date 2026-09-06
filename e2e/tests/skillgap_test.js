Feature('Phân tích khoảng trống kỹ năng');

/**
 * Module này từng crash vì LazyInitializationException (commit 80c601a) và
 * export PDF đã phải sửa 2 lần -> nhóm rủi ro cao nhất của hệ thống.
 */

/** Chuẩn bị: tài khoản mới đã hoàn tất onboarding. */
async function newStudentReady({ I, registerPage, loginPage, onboardingPage }) {
  const email = await I.uniqueEmail('skg');
  registerPage.register('Sinh Vien SkillGap', email, 'MatKhau@123');
  loginPage.login(email, 'MatKhau@123');
  onboardingPage.completeAll(3);
  return email;
}

Scenario(
  'TC-SKG-001 | Xem được kết quả phân tích khoảng trống kỹ năng @P1',
  async ({ I, registerPage, loginPage, onboardingPage, skillGapPage }) => {
    await newStudentReady({ I, registerPage, loginPage, onboardingPage });

    skillGapPage.open();

    // Trang render được nghĩa là không dính LazyInitializationException.
    skillGapPage.seeAnalysis();
  },
);

Scenario(
  'TC-SKG-002 | Lưu báo cáo phân tích thành công @P1',
  async ({ I, registerPage, loginPage, onboardingPage, skillGapPage }) => {
    await newStudentReady({ I, registerPage, loginPage, onboardingPage });

    skillGapPage.open();
    // Phải chạy phân tích trước: form lưu báo cáo chỉ render khi đã có kết quả.
    skillGapPage.chonLoTrinhDauTien();
    skillGapPage.saveReport();

    I.seeInCurrentUrl('/skill-gap');
  },
);
