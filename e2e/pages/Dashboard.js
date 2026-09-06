const { I } = inject();

/** Trang tổng quan sau khi đăng nhập + hoàn tất onboarding. */
module.exports = {
  url: '/dashboard',

  // Bám vào data-testid chứ KHÔNG bám vào câu chữ hiển thị. Kịch bản cũ tìm chuỗi
  // "node hoàn thành" và "kỹ năng còn thiếu"; giao diện được sửa lại thành
  // "Tiến độ Lộ trình" và "Kỹ năng nắm vững" là kịch bản đỏ ngay, dù chức năng
  // vẫn chạy đúng. Định danh riêng cho kiểm thử không đổi theo lần thiết kế lại.
  tienDoLoTrinh: '[data-testid=tien-do-lo-trinh]',
  soNodeHoanThanh: '[data-testid=so-node-hoan-thanh]',
  soKyNangNamVung: '[data-testid=so-ky-nang-nam-vung]',

  open() {
    I.amOnPage(this.url);
  },

  seeDashboard() {
    I.seeInCurrentUrl('/dashboard');
    I.see('Hoạt động gần đây');
  },

  /** Ba ô thống kê phải hiện đủ: dashboard gom dữ liệu từ roadmap + skill gap. */
  seeThongKe() {
    I.seeElement(this.tienDoLoTrinh);
    I.seeElement(this.soNodeHoanThanh);
    I.seeElement(this.soKyNangNamVung);
  },
};
