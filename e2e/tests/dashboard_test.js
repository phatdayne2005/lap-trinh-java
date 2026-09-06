Feature('Trang tổng quan');

Scenario(
  'TC-DSB-001 | Dashboard hiển thị thống kê sau khi hoàn tất onboarding @P1',
  async ({ I, registerPage, loginPage, onboardingPage, dashboardPage }) => {
    const email = await I.uniqueEmail('dsb');
    registerPage.register('Sinh Vien Dashboard', email, 'MatKhau@123');
    loginPage.login(email, 'MatKhau@123');
    onboardingPage.completeAll(3);

    dashboardPage.open();
    dashboardPage.seeDashboard();

    // Dashboard gom dữ liệu từ roadmap + skill gap + activity log.
    // Kiểm theo data-testid thay vì câu chữ: nhãn hiển thị đã đổi một lần
    // ("node hoàn thành" -> "Tiến độ Lộ trình") và làm kịch bản đỏ oan.
    dashboardPage.seeThongKe();
  },
);
