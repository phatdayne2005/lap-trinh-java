package vn.uth.careercompass.portfolio.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Comparator;
import java.util.TreeSet;

import lombok.RequiredArgsConstructor;
import vn.uth.careercompass.kernel.entity.User;
import vn.uth.careercompass.kernel.entity.UserSkill;
import vn.uth.careercompass.kernel.repository.UserRepository;
import vn.uth.careercompass.kernel.repository.UserSkillRepository;
import vn.uth.careercompass.mentor.service.LlmClient;
import vn.uth.careercompass.portfolio.dto.OwnerInfoDTO;
import vn.uth.careercompass.portfolio.entity.GitHubProfile;
import vn.uth.careercompass.portfolio.entity.ProjectRepository;
import vn.uth.careercompass.portfolio.repository.GitHubProfileRepository;
import vn.uth.careercompass.portfolio.repository.ProjectRepositoryRepository;
import vn.uth.careercompass.roadmap.entity.ProgressStatus;
import vn.uth.careercompass.roadmap.repository.UserNodeProgressRepository;

/**
 * Đồng bộ repo GitHub công khai của user, tóm tắt README bằng AI (FR5.1/5.2), và cung cấp
 * dữ liệu cho trang portfolio cá nhân + trang public chia sẻ qua slug (FR5.3).
 *
 * <p>Dùng LLM chung của P3 ({@link LlmClient}) để tóm tắt; nếu LLM lỗi (thiếu key/API down) thì
 * fallback tóm tắt cơ bản để KHÔNG chặn luồng sync.</p>
 */
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final GitHubProfileRepository gitHubProfileRepository;
    private final ProjectRepositoryRepository projectRepositoryRepository;
    private final UserRepository userRepository;
    private final UserSkillRepository userSkillRepository;
    private final UserNodeProgressRepository userNodeProgressRepository;
    private final LlmClient llmClient;
    private final RestTemplate restTemplate = new RestTemplate();

    /** Ẩn/hiện 1 repo trên trang portfolio công khai (đảo isPublic). Kiểm repo thuộc về user. */
    @Transactional
    public void toggleRepoVisibility(User user, Long repoId) {
        gitHubProfileRepository.findByUserId(user.getId()).ifPresent(profile ->
                projectRepositoryRepository.findById(repoId).ifPresent(repo -> {
                    if (repo.getGithubProfile() != null && repo.getGithubProfile().getId().equals(profile.getId())) {
                        repo.setIsPublic(!repo.isIsPublic());
                        projectRepositoryRepository.save(repo);
                    }
                }));
    }

    /** Gom thông tin chủ portfolio (mục tiêu nghề, kỹ năng, điểm mạnh) cho trang công khai. */
    @Transactional(readOnly = true)
    public OwnerInfoDTO getOwnerInfo(Long userId) {
        User owner = userRepository.findById(userId).orElse(null);
        if (owner == null) {
            return new OwnerInfoDTO(null, List.of(), null);
        }
        String careerGoal = owner.getCareerRole() != null ? owner.getCareerRole().getName() : null;
        // Kỹ năng = kỹ năng tự khai (UserSkill) + kỹ năng đã hoàn thành trong Roadmap (nhất quán với Skill Gap)
        TreeSet<String> skills = new TreeSet<>(Comparator.naturalOrder());
        userSkillRepository.findByUserWithSkill(owner).forEach(us -> skills.add(us.getSkill().getName()));
        userNodeProgressRepository.findByUserAndStatusWithSkill(owner, ProgressStatus.DONE)
                .forEach(p -> skills.add(p.getSkillNode().getSkill().getName()));
        return new OwnerInfoDTO(careerGoal, List.copyOf(skills), owner.getTranscriptSummary());
    }

    // ─── Đọc dữ liệu ─────────────────────────────────────────────────────────
    public Optional<GitHubProfile> getProfileByUser(User user) {
        return gitHubProfileRepository.findByUserId(user.getId());
    }

    public Optional<GitHubProfile> getProfileBySlug(String slug) {
        return gitHubProfileRepository.findBySlug(slug);
    }

    public List<ProjectRepository> getRepos(Long profileId) {
        return projectRepositoryRepository.findByGithubProfileId(profileId);
    }

    public List<ProjectRepository> getPublicRepos(Long profileId) {
        return projectRepositoryRepository.findByGithubProfileIdAndIsPublicTrue(profileId);
    }

    // ─── Đồng bộ ─────────────────────────────────────────────────────────────
    /**
     * Đồng bộ toàn bộ repo công khai của {@code githubUsername} về portfolio của {@code user}.
     * Xoá repo cũ trước (tránh nhân đôi khi sync lại), rồi fetch + tóm tắt README.
     */
    @Transactional
    public List<ProjectRepository> syncGithubRepositories(User user, String githubUsername) {
        if (githubUsername == null || githubUsername.trim().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập GitHub username!");
        }
        String username = githubUsername.trim();

        GitHubProfile profile = gitHubProfileRepository.findByUserId(user.getId())
                .orElseGet(() -> GitHubProfile.builder()
                        .userId(user.getId())
                        .slug(generateSlug(username))
                        .build());
        profile.setGithubUsername(username);
        if (profile.getSlug() == null) {
            profile.setSlug(generateSlug(username));
        }
        profile = gitHubProfileRepository.save(profile);

        // Xoá repo cũ để sync lại không bị nhân đôi
        projectRepositoryRepository.deleteByGithubProfileId(profile.getId());

        List<Map<String, Object>> response = fetchRepos(username);

        // Lấy README và nhờ LLM tóm tắt cho TẤT CẢ repo cùng lúc, TRƯỚC khi ghi CSDL.
        // Phần này thuần chờ mạng nên chạy song song rút ngắn rất nhiều; còn việc ghi
        // vẫn tuần tự trên luồng gọi vì EntityManager của JPA không an toàn đa luồng.
        Map<String, String> tomTat = tomTatSongSong(username, response);

        java.util.List<ProjectRepository> saved = new java.util.ArrayList<>();
        for (Map<String, Object> repoData : response) {
            String repoName = (String) repoData.get("name");
            String htmlUrl = (String) repoData.get("html_url");
            String description = (String) repoData.get("description");
            Object starsRaw = repoData.get("stargazers_count");
            int stars = (starsRaw instanceof Number number) ? number.intValue() : 0;

            String aiSummary = tomTat.get(repoName);

            saved.add(projectRepositoryRepository.save(ProjectRepository.builder()
                    .repoName(repoName)
                    .htmlUrl(htmlUrl)
                    .description(description)
                    .stars(stars)
                    .isPublic(true)
                    .githubProfile(profile)
                    .aiSummary(aiSummary)
                    .build()));
        }
        return saved;
    }

    /**
     * Số luồng chạy song song khi lấy README và gọi LLM.
     *
     * <p>Chọn 6 chứ không phải càng nhiều càng tốt: Gemini bản miễn phí giới hạn tần suất,
     * đẩy quá nhiều yêu cầu cùng lúc sẽ nhận 429 rồi rơi vào nhánh thử lại của
     * {@link LlmClient}, hoá ra còn chậm hơn. Sáu luồng đủ để một tài khoản 30 repo —
     * mức trần mà NFR-P03 nêu — chạy hết trong 5 đợt.
     */
    private static final int SO_LUONG_SONG_SONG = 6;

    /**
     * Lấy README rồi nhờ LLM tóm tắt cho mọi repo cùng lúc, trả về map {@code tên repo → tóm tắt}.
     *
     * <p><b>Vì sao cần:</b> vòng lặp cũ làm tuần tự từng repo, mỗi repo tốn 2 lệnh HTTP lấy
     * README cộng 1 lệnh gọi LLM. Đo thực tế với tài khoản 9 repo: 84 giây, tức khoảng 9 giây
     * mỗi repo, vượt xa ngưỡng 30 giây của NFR-P03. Toàn bộ thời gian đó là ngồi chờ mạng chứ
     * không phải tính toán, nên chạy song song cắt được gần hết.
     *
     * <p>Mỗi repo vẫn tự nuốt lỗi riêng trong {@link #generateAiSummary}, nên một repo hỏng
     * không kéo đổ cả lần đồng bộ.
     */
    private Map<String, String> tomTatSongSong(String username, List<Map<String, Object>> repos) {
        if (repos.isEmpty()) {
            return Map.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(SO_LUONG_SONG_SONG, repos.size()),
                r -> {
                    Thread t = new Thread(r, "dong-bo-github");
                    t.setDaemon(true);   // không giữ JVM sống nếu app tắt giữa chừng
                    return t;
                });
        try {
            List<CompletableFuture<Map.Entry<String, String>>> viec = repos.stream()
                    .map(repoData -> CompletableFuture.supplyAsync(() -> {
                        String repoName = (String) repoData.get("name");
                        String readme = fetchReadmeContent(username, repoName);
                        String description = (String) repoData.get("description");
                        return Map.entry(repoName, generateAiSummary(repoName, readme, description));
                    }, pool))
                    .toList();

            Map<String, String> ketQua = new java.util.HashMap<>();
            for (CompletableFuture<Map.Entry<String, String>> f : viec) {
                Map.Entry<String, String> e = f.join();
                ketQua.put(e.getKey(), e.getValue());
            }
            return ketQua;
        } finally {
            pool.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchRepos(String username) {
        String url = "https://api.github.com/users/" + username + "/repos?per_page=100&sort=updated";
        try {
            List<Map<String, Object>> repos = restTemplate.getForObject(url, List.class);
            return repos == null ? List.of() : repos;
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Không tìm thấy user GitHub '" + username + "'.");
        } catch (RestClientException e) {
            throw new IllegalStateException("Không gọi được GitHub API (có thể bị giới hạn tần suất). Thử lại sau.", e);
        }
    }

    private String fetchReadmeContent(String username, String repoName) {
        for (String branch : new String[]{"main", "master"}) {
            try {
                String content = restTemplate.getForObject(
                        "https://raw.githubusercontent.com/" + username + "/" + repoName + "/" + branch + "/README.md",
                        String.class);
                if (content != null && !content.isBlank()) {
                    return content;
                }
            } catch (RestClientException ignored) {
                // thử branch tiếp theo
            }
        }
        return "";
    }

    private String generateAiSummary(String repoName, String readmeContent, String description) {
        String context = (readmeContent != null && !readmeContent.isBlank())
                ? readmeContent
                : (description != null ? description : "");
        if (context.isBlank()) {
            return "Dự án chưa có README/mô tả để AI tóm tắt.";
        }
        String truncated = context.length() > 3000 ? context.substring(0, 3000) : context;
        try {
            return llmClient.ask("Tóm tắt ngắn gọn 2-3 câu bằng tiếng Việt về mục tiêu và công nghệ chính của "
                    + "dự án GitHub \"" + repoName + "\" dựa trên README/mô tả sau:\n\n" + truncated);
        } catch (Exception e) {
            // LLM lỗi (thiếu key/API down) → fallback, không chặn sync
            String excerpt = truncated.length() > 200 ? truncated.substring(0, 200) + "..." : truncated;
            return "[Tóm tắt cơ bản] " + repoName + " — " + excerpt.replaceAll("\\s+", " ").trim();
        }
    }

    private String generateSlug(String username) {
        String base = username.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
