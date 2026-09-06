package vn.uth.careercompass.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest req;
    private RedirectAttributes flash;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        req = Mockito.mock(HttpServletRequest.class);
        flash = new RedirectAttributesModelMap(); 
    }

    @Test
    void api_IllegalArgumentException_returns400Json() {
        when(req.getRequestURI()).thenReturn("/api/roadmap");
        when(req.getMethod()).thenReturn("GET");

        IllegalArgumentException ex = new IllegalArgumentException("test error");
        Object result = handler.xuLyThamSoKhongHopLe(ex, req, flash);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "test error"));
    }

    @Test
    void api_IllegalStateException_returns409Json() {
        when(req.getRequestURI()).thenReturn("/api/users");
        when(req.getMethod()).thenReturn("POST");

        IllegalStateException ex = new IllegalStateException("state error");
        Object result = handler.xuLyTrangThaiKhongHopLe(ex, req, flash);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "state error"));
    }

    @Test
    void api_DataIntegrityViolationException_returns409Json_hiddenMessage() {
        when(req.getRequestURI()).thenReturn("/api/skills");
        when(req.getMethod()).thenReturn("PUT");

        DataIntegrityViolationException ex = new DataIntegrityViolationException("secret sql error");
        Object result = handler.xuLyTrungDuLieu(ex, req, flash);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Do \u1EEF và \u0111\u00E3 có thể gặp vấn đề mã hóa trong chuỗi assert, ta dùng trực tiếp chuỗi như trong file gốc
        assertThat(response.getBody()).isEqualTo(Map.of("error", "D\u1eef li\u1ec7u \u0111\u00e3 t\u1ed3n t\u1ea1i ho\u1eb7c vi ph\u1ea1m r\u00e0ng bu\u1ed9c. Vui l\u00f2ng ki\u1ec3m tra l\u1ea1i."));
    }

    @Test
    void webWrite_validReferer_redirectsToReferer() {
        when(req.getRequestURI()).thenReturn("/admin/users");
        when(req.getMethod()).thenReturn("POST");
        when(req.getHeader("Referer")).thenReturn("http://localhost/admin/users/new");
        when(req.getScheme()).thenReturn("http");
        when(req.getServerName()).thenReturn("localhost");

        IllegalArgumentException ex = new IllegalArgumentException("invalid input");
        Object result = handler.xuLyThamSoKhongHopLe(ex, req, flash);

        assertThat(result).isEqualTo("redirect:http://localhost/admin/users/new");
        assertThat(flash.getFlashAttributes().get("error")).isEqualTo("invalid input");
    }

    @Test
    void webWrite_noReferer_redirectsToRoot() {
        when(req.getRequestURI()).thenReturn("/admin/users");
        when(req.getMethod()).thenReturn("PUT");
        when(req.getHeader("Referer")).thenReturn(null);

        IllegalArgumentException ex = new IllegalArgumentException("invalid input");
        Object result = handler.xuLyThamSoKhongHopLe(ex, req, flash);

        assertThat(result).isEqualTo("redirect:/");
    }

    @Test
    void webWrite_invalidReferer_redirectsToRoot() {
        when(req.getRequestURI()).thenReturn("/admin/users");
        when(req.getMethod()).thenReturn("DELETE");
        when(req.getHeader("Referer")).thenReturn("http://evil.com/phishing");
        when(req.getScheme()).thenReturn("http");
        when(req.getServerName()).thenReturn("localhost");

        IllegalArgumentException ex = new IllegalArgumentException("invalid input");
        Object result = handler.xuLyThamSoKhongHopLe(ex, req, flash);

        assertThat(result).isEqualTo("redirect:/");
    }

    @Test
    void webWrite_patch_redirectsToRoot() {
        when(req.getRequestURI()).thenReturn("/admin/users");
        when(req.getMethod()).thenReturn("PATCH");

        IllegalArgumentException ex = new IllegalArgumentException("invalid input");
        Object result = handler.xuLyThamSoKhongHopLe(ex, req, flash);

        assertThat(result).isEqualTo("redirect:/");
    }

    @Test
    void webRead_get_returnsStatusOnly() {
        when(req.getRequestURI()).thenReturn("/dashboard");
        when(req.getMethod()).thenReturn("GET");

        IllegalArgumentException ex = new IllegalArgumentException("not found");
        Object result = handler.xuLyThamSoKhongHopLe(ex, req, flash);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNull();
    }
}
