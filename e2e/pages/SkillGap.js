const { I } = inject();

/**
 * Trang phân tích khoảng trống kỹ năng (FR3.1, FR3.2, FR3.3).
 * Module này TỪNG CRASH vì LazyInitializationException -> ưu tiên test cao.
 */
module.exports = {
  url: '/skill-gap',

  templateSelect: '#templateId',
  skillSelect: '#skillId',
  addSkillBtn: 'form[action*="/skill-gap/skills"] button[type=submit]',
  // Bám vào data-testid của form: bố cục trang đã đổi khiến bộ chọn dựng theo
  // thuộc tính action không tìm thấy nút trong 10 giây chờ.
  saveReportForm: '[data-testid=form-luu-bao-cao]',
  saveReportBtn: '[data-testid=form-luu-bao-cao] button[type=submit]',

  open() {
    I.amOnPage(this.url);
  },

  /**
   * Chọn một lộ trình để hệ thống chạy phân tích.
   *
   * Bắt buộc trước khi lưu báo cáo: toàn bộ khối kết quả nằm trong
   * th:if="${analysis != null}", nên với tài khoản vừa tạo mà chưa chọn lộ trình thì
   * form lưu báo cáo KHÔNG tồn tại trong DOM. Kịch bản cũ mở trang rồi chờ thẳng nút
   * submit nên hết thời gian chờ mà không bao giờ thấy.
   *
   * Ô select có onchange="this.form.submit()" nên chỉ cần chọn là trang tự nạp lại.
   */
  chonLoTrinhDauTien() {
    I.waitForElement(this.templateSelect, 10);
    I.selectOption(this.templateSelect, { index: 0 });
    I.waitForElement(this.saveReportForm, 15);
  },

  seeAnalysis() {
    I.seeInCurrentUrl('/skill-gap');
    I.seeElement(this.templateSelect);
  },

  /**
   * Lưu kết quả phân tích thành báo cáo (tiền đề để tải PDF).
   *
   * Chờ theo TRẠNG THÁI: form chỉ hiện sau khi khối phân tích render xong, nên đợi
   * chính form đó thay vì đợi cứng rồi bấm. Cách cũ chờ nút submit dựng theo thuộc
   * tính action nên hết 10 giây vẫn không thấy khi bố cục trang thay đổi.
   */
  saveReport() {
    I.waitForElement(this.saveReportForm, 15);
    I.waitForVisible(this.saveReportBtn, 10);
    I.click(this.saveReportBtn);
  },
};
