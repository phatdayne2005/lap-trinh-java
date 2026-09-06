package vn.uth.careercompass.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Chuyển lỗi nghiệp vụ thành phản hồi có nghĩa thay vì 500.
 *
 * <p><b>Vì sao cần:</b> trước đây dự án không có {@code @ControllerAdvice} nào bắt ngoại lệ.
 * Mọi {@code IllegalArgumentException} từ tầng service — kể cả những lỗi bình thường như
 * "không tìm thấy người dùng" — đều rơi xuống trang lỗi mặc định và trả HTTP 500 kèm
 * nguyên stack trace: tên package {@code vn.uth.careercompass}, tên lớp, tên method, số dòng,
 * và cả câu {@code insert into skill_nodes ...} khi vi phạm ràng buộc khoá duy nhất.
 * Đó là vi phạm NFR-S05.
 *
 * <p><b>Cách xử lý</b> tuỳ theo loại yêu cầu:
 * <ul>
 *   <li>{@code /api/**} — trả JSON {@code {"error": "..."}} kèm mã trạng thái đúng ngữ nghĩa.
 *       Hai lớp {@code RoadmapController} và {@code SkillGapController} phục vụ nhóm này.</li>
 *   <li>Form gửi lên từ giao diện (POST/PUT/DELETE) — chuyển hướng về đúng trang vừa thao tác
 *       kèm {@code flash "error"}, giống quy ước sẵn có trong các controller.</li>
 *   <li>Yêu cầu GET trang HTML — trả về mã trạng thái để Spring hiển thị {@code error.html}.
 *       KHÔNG chuyển hướng, vì chuyển hướng về chính trang đang lỗi sẽ tạo vòng lặp.</li>
 * </ul>
 *
 * <p>Dùng {@code @ControllerAdvice} chứ KHÔNG dùng {@code @RestControllerAdvice}: biến thể Rest
 * ngầm gắn {@code @ResponseBody}, khiến chuỗi {@code "redirect:/…"} bị trả nguyên văn ra thân
 * phản hồi kèm mã 200 thay vì thực hiện chuyển hướng. Đã gặp đúng lỗi này khi thử lần đầu.
 * Với {@code @ControllerAdvice}, kiểu trả về {@code Object} vẫn nhận {@code ResponseEntity}
 * cho nhánh JSON, đồng thời hiểu {@code "redirect:…"} cho nhánh giao diện.
 *
 * <p>KHÔNG bắt {@code org.springframework.web.server.ResponseStatusException} — loại đó đã
 * mang sẵn mã trạng thái do lập trình viên chọn, để Spring xử lý nguyên trạng.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /** Dữ liệu vào không hợp lệ hoặc không tìm thấy bản ghi. */
    @ExceptionHandler(IllegalArgumentException.class)
    public Object xuLyThamSoKhongHopLe(IllegalArgumentException ex, HttpServletRequest req,
                                       RedirectAttributes flash) {
        return phanHoi(HttpStatus.BAD_REQUEST, ex, req, flash);
    }

    /** Thao tác không hợp lệ với trạng thái hiện tại, ví dụ tự khoá tài khoản của chính mình. */
    @ExceptionHandler(IllegalStateException.class)
    public Object xuLyTrangThaiKhongHopLe(IllegalStateException ex, HttpServletRequest req,
                                          RedirectAttributes flash) {
        return phanHoi(HttpStatus.CONFLICT, ex, req, flash);
    }

    /** Vi phạm ràng buộc CSDL, thường là khoá duy nhất khi tạo trùng bản ghi. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Object xuLyTrungDuLieu(DataIntegrityViolationException ex, HttpServletRequest req,
                                  RedirectAttributes flash) {
        // Thông điệp gốc chứa nguyên câu SQL nên KHÔNG được đưa ra ngoài (NFR-S05).
        log.warn("Vi phạm ràng buộc CSDL tại {} {}", req.getMethod(), req.getRequestURI(), ex);
        return phanHoi(HttpStatus.CONFLICT,
                "Dữ liệu đã tồn tại hoặc vi phạm ràng buộc. Vui lòng kiểm tra lại.", req, flash);
    }

    // ================================================================
    // Dựng phản hồi
    // ================================================================

    private Object phanHoi(HttpStatus status, Exception ex, HttpServletRequest req,
                           RedirectAttributes flash) {
        log.warn("{} tại {} {}: {}", status.value(), req.getMethod(), req.getRequestURI(),
                ex.getMessage());
        return phanHoi(status, ex.getMessage(), req, flash);
    }

    private Object phanHoi(HttpStatus status, String thongDiep, HttpServletRequest req,
                           RedirectAttributes flash) {
        if (laApi(req)) {
            return ResponseEntity.status(status).body(Map.of("error", thongDiep));
        }
        if (laThaoTacGhi(req)) {
            flash.addFlashAttribute("error", thongDiep);
            return "redirect:" + trangQuayVe(req);
        }
        // GET trang HTML: để Spring hiển thị error.html với đúng mã trạng thái.
        return ResponseEntity.status(status).build();
    }

    private boolean laApi(HttpServletRequest req) {
        return req.getRequestURI().startsWith("/api/");
    }

    private boolean laThaoTacGhi(HttpServletRequest req) {
        String m = req.getMethod();
        return "POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m) || "DELETE".equals(m);
    }

    /**
     * Trang để quay về sau khi thao tác lỗi. Ưu tiên Referer để người dùng ở lại đúng màn hình
     * vừa bấm. Chỉ nhận Referer cùng host để tránh chuyển hướng sang trang ngoài do kẻ tấn công
     * đặt (open redirect).
     */
    private String trangQuayVe(HttpServletRequest req) {
        String referer = req.getHeader("Referer");
        if (referer != null) {
            String goc = req.getScheme() + "://" + req.getServerName();
            if (referer.startsWith(goc)) {
                return referer;
            }
        }
        return "/";
    }
}
