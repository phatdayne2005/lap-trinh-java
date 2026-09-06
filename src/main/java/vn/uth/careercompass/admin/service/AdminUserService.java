package vn.uth.careercompass.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.uth.careercompass.admin.dto.UserAdminDto;
import vn.uth.careercompass.admin.mapper.UserAdminMapper;
import vn.uth.careercompass.kernel.entity.Role;
import vn.uth.careercompass.kernel.entity.RoleName;
import vn.uth.careercompass.kernel.entity.User;
import vn.uth.careercompass.kernel.repository.RoleRepository;
import vn.uth.careercompass.kernel.repository.UserRepository;
import vn.uth.careercompass.kernel.repository.UserSkillRepository;
import vn.uth.careercompass.kernel.repository.ActivityLogRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service quản trị người dùng cho Admin (P7).
 *
 * <p>Xử lý lỗi: validation ném {@link IllegalArgumentException} với message rõ ràng cho controller
 * hiển thị lại; không bọc {@code catch(Exception)} nuốt lỗi thành RuntimeException chung chung.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserSkillRepository userSkillRepository;
    private final ActivityLogRepository activityLogRepository;

    public List<UserAdminDto> getAllUsers() {
        return userRepository.findAllWithRoleAndCareerRole().stream()
                .map(UserAdminMapper::toDto)
                .collect(Collectors.toList());
    }

    public List<UserAdminDto> searchUsers(String keyword) {
        return userRepository.searchUsers(keyword).stream()
                .map(UserAdminMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public UserAdminDto toggleUserStatus(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng có ID: " + userId));
        requireNotCurrentUser(user, "Bạn không thể tự khóa tài khoản của chính mình!");

        user.setEnabled(!user.getEnabled());
        return UserAdminMapper.toDto(userRepository.save(user));
    }

    @Transactional
    public User changeUserRole(Long userId, String roleNameStr) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng có ID: " + userId));
        // DEF-001: thiếu đúng dòng này nên quản trị viên tự hạ được vai trò của chính mình
        // xuống STUDENT, mất luôn quyền vào /admin và KHÔNG có đường quay lại qua giao diện —
        // muốn khôi phục phải sửa thẳng trong cơ sở dữ liệu. Hai thao tác nguy hiểm tương tự
        // là tự khoá và tự xoá thì đã được chặn từ trước; đổi vai trò bị bỏ sót.
        requireNotCurrentUser(user, "Bạn không thể tự đổi vai trò của chính mình!");
        RoleName roleName = RoleName.valueOf(roleNameStr.toUpperCase());
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy vai trò: " + roleName));
        user.setRole(role);
        return userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng có ID: " + userId));
        requireNotCurrentUser(user, "Bạn không thể tự xóa tài khoản của chính mình!");

        // Xóa các bảng liên quan trước (tránh vi phạm khoá ngoại)
        userSkillRepository.deleteByUser(user);
        activityLogRepository.deleteByUser(user);
        userRepository.delete(user);
    }

    /** Chặn admin thao tác lên chính tài khoản đang đăng nhập (tự khoá / tự xoá). */
    private void requireNotCurrentUser(User user, String message) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && user.getEmail().equalsIgnoreCase(auth.getName())) {
            throw new IllegalArgumentException(message);
        }
    }
}
