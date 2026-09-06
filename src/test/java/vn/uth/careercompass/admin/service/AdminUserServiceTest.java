package vn.uth.careercompass.admin.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.uth.careercompass.admin.dto.UserAdminDto;
import vn.uth.careercompass.kernel.entity.Role;
import vn.uth.careercompass.kernel.entity.RoleName;
import vn.uth.careercompass.kernel.entity.User;
import vn.uth.careercompass.kernel.repository.ActivityLogRepository;
import vn.uth.careercompass.kernel.repository.RoleRepository;
import vn.uth.careercompass.kernel.repository.UserRepository;
import vn.uth.careercompass.kernel.repository.UserSkillRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test cho {@link AdminUserService}.
 *
 * <p>ĐIỂM MỚI của file này: service đọc {@link SecurityContextHolder} (context bảo mật của
 * Spring Security) để biết admin ĐANG đăng nhập là ai, nhằm CHẶN admin tự khóa/tự xóa chính mình.
 * Trong unit test không có Spring Security thật, nên ta tự "đặt" một Authentication vào context
 * bằng {@link SecurityContextHolder}, rồi {@link #tearDown()} dọn sạch sau mỗi test để tránh
 * rò rỉ trạng thái sang test khác (context là biến static toàn cục theo thread).
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserSkillRepository userSkillRepository;
    @Mock
    private ActivityLogRepository activityLogRepository;

    @InjectMocks
    private AdminUserService adminUserService;

    // Dọn SecurityContext sau MỖI test. WHY: nếu 1 test set Authentication rồi không xóa,
    // test sau (chạy chung thread) sẽ "thấy" auth cũ -> gây lỗi giả (flaky test).
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Helper tạo User tối thiểu đủ cho mapper (role + enabled + email). */
    private User buildUser(Long id, String email, boolean enabled) {
        Role role = Role.builder().name(RoleName.STUDENT).build();
        return User.builder()
                .id(id)
                .fullName("Nguyen Van " + id)
                .email(email)
                .role(role)
                .enabled(enabled)
                .build();
    }

    /** Giả lập admin đang đăng nhập với email cho trước (đặt vào SecurityContext). */
    private void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null));
    }

    // ============================================================================
    // getAllUsers()
    // ============================================================================
    @Test
    void getAllUsers_mapsEntitiesToDto() {
        // Given: repo trả về 2 user
        List<User> users = List.of(
                buildUser(1L, "a@uth.edu.vn", true),
                buildUser(2L, "b@uth.edu.vn", false));
        when(userRepository.findAllWithRoleAndCareerRole()).thenReturn(users);

        // When
        List<UserAdminDto> result = adminUserService.getAllUsers();

        // Then: đúng số lượng + mapper chuyển đúng field (email, roleName, enabled)
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEmail()).isEqualTo("a@uth.edu.vn");
        assertThat(result.get(0).getRoleName()).isEqualTo("STUDENT");
        assertThat(result.get(1).getEnabled()).isFalse();
    }

    // ============================================================================
    // searchUsers(keyword)
    // ============================================================================
    @Test
    void searchUsers_delegatesToRepositoryAndMaps() {
        when(userRepository.searchUsers("nguyen")).thenReturn(List.of(buildUser(1L, "a@uth.edu.vn", true)));

        List<UserAdminDto> result = adminUserService.searchUsers("nguyen");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFullName()).isEqualTo("Nguyen Van 1");
    }

    // ============================================================================
    // toggleUserStatus(userId)
    // ============================================================================
    @Test
    void toggleUserStatus_whenNotCurrentUser_flipsEnabledAndSaves() {
        // Given: user đang enabled=true, admin đăng nhập là NGƯỜI KHÁC
        User user = buildUser(1L, "target@uth.edu.vn", true);
        loginAs("admin@uth.edu.vn"); // khác email target -> được phép
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        // save trả lại chính user (mapper đọc trạng thái sau khi toggle)
        when(userRepository.save(user)).thenReturn(user);

        // When
        UserAdminDto dto = adminUserService.toggleUserStatus(1L);

        // Then: enabled bị lật true -> false
        assertThat(user.getEnabled()).isFalse();
        assertThat(dto.getEnabled()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void toggleUserStatus_whenNoAuthentication_stillAllowed() {
        // Given: KHÔNG có ai đăng nhập (auth == null) -> guard requireNotCurrentUser bỏ qua.
        // WHY test nhánh này: điều kiện guard là "auth != null && ...", nên auth null phải cho qua.
        User user = buildUser(1L, "target@uth.edu.vn", false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        UserAdminDto dto = adminUserService.toggleUserStatus(1L);

        assertThat(dto.getEnabled()).isTrue(); // false -> true
    }

    @Test
    void toggleUserStatus_whenTargetIsCurrentUser_throwsAndDoesNotSave() {
        // Given: admin cố khóa CHÍNH MÌNH (cùng email) -> phải bị chặn
        User self = buildUser(1L, "admin@uth.edu.vn", true);
        loginAs("admin@uth.edu.vn");
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> adminUserService.toggleUserStatus(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bạn không thể tự khóa tài khoản của chính mình!");

        // Không được đổi trạng thái, không lưu
        assertThat(self.getEnabled()).isTrue();
        verify(userRepository, never()).save(any());
    }

    @Test
    void toggleUserStatus_whenUserNotFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.toggleUserStatus(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Không tìm thấy người dùng có ID: 99");

        verify(userRepository, never()).save(any());
    }

    // ============================================================================
    // changeUserRole(userId, roleNameStr)
    // ============================================================================
    @Test
    void changeUserRole_whenValid_setsRoleAndSaves() {
        // Given: user tồn tại, role COUNSELOR đã seed. roleNameStr viết thường -> service tự toUpperCase.
        User user = buildUser(1L, "a@uth.edu.vn", true);
        Role counselorRole = Role.builder().name(RoleName.COUNSELOR).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.COUNSELOR)).thenReturn(Optional.of(counselorRole));
        when(userRepository.save(user)).thenReturn(user);

        User result = adminUserService.changeUserRole(1L, "counselor");

        assertThat(result.getRole()).isEqualTo(counselorRole);
        verify(userRepository).save(user);
    }

    @Test
    void changeUserRole_whenRoleNameInvalid_throwsIllegalArgument() {
        // Given: user tồn tại nhưng chuỗi role không map được enum -> RoleName.valueOf ném IllegalArgumentException.
        // Không stub roleRepository vì luồng ném lỗi TRƯỚC khi tra role -> tránh stub thừa.
        User user = buildUser(1L, "a@uth.edu.vn", true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> adminUserService.changeUserRole(1L, "SUPERUSER"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void changeUserRole_whenRoleNotSeeded_throws() {
        // Given: chuỗi hợp lệ (ADMIN) nhưng DB chưa có row Role tương ứng.
        User user = buildUser(1L, "a@uth.edu.vn", true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(roleRepository.findByName(RoleName.ADMIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.changeUserRole(1L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Không tìm thấy vai trò: ADMIN");

        verify(userRepository, never()).save(any());
    }

    @Test
    void changeUserRole_whenUserNotFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.changeUserRole(99L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Không tìm thấy người dùng có ID: 99");
    }

    /**
     * Test hồi quy cho DEF-001.
     *
     * <p>Trước khi sửa, quản trị viên tự hạ được vai trò của chính mình xuống STUDENT rồi
     * mất luôn quyền vào {@code /admin}, và không có đường quay lại qua giao diện — phải
     * sửa thẳng trong cơ sở dữ liệu. Kịch bản giao diện {@code TC-ADM-003} đã tái hiện
     * đúng tình huống này: sau khi chạy, tài khoản admin mang vai trò STUDENT thật.
     *
     * <p>Hai thao tác nguy hiểm tương tự là tự khoá và tự xoá đã được chặn từ trước; riêng
     * đổi vai trò bị bỏ sót.
     */
    @Test
    void changeUserRole_whenTargetIsCurrentUser_throwsAndDoesNotSave() {
        User self = buildUser(1L, "admin@uth.edu.vn", true);
        loginAs("admin@uth.edu.vn");
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> adminUserService.changeUserRole(1L, "student"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bạn không thể tự đổi vai trò của chính mình!");

        verify(userRepository, never()).save(any(User.class));
    }

    // ============================================================================
    // deleteUser(userId)
    // ============================================================================
    @Test
    void deleteUser_whenNotCurrentUser_deletesDependenciesThenUser() {
        // Given: xóa user khác mình. Service phải dọn bảng phụ (user_skills, activity_logs)
        // TRƯỚC khi xóa user để không vi phạm khóa ngoại.
        User user = buildUser(1L, "target@uth.edu.vn", true);
        loginAs("admin@uth.edu.vn");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        adminUserService.deleteUser(1L);

        // Then: đủ 3 lệnh xóa, đúng đối tượng user
        verify(userSkillRepository).deleteByUser(user);
        verify(activityLogRepository).deleteByUser(user);
        verify(userRepository).delete(user);
    }

    @Test
    void deleteUser_whenTargetIsCurrentUser_throwsAndDeletesNothing() {
        User self = buildUser(1L, "admin@uth.edu.vn", true);
        loginAs("admin@uth.edu.vn");
        when(userRepository.findById(1L)).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> adminUserService.deleteUser(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bạn không thể tự xóa tài khoản của chính mình!");

        // Tuyệt đối không được xóa gì
        verify(userSkillRepository, never()).deleteByUser(any());
        verify(activityLogRepository, never()).deleteByUser(any());
        verify(userRepository, never()).delete(any());
    }

    @Test
    void deleteUser_whenUserNotFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.deleteUser(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Không tìm thấy người dùng có ID: 99");

        verify(userRepository, never()).delete(any());
    }
}
