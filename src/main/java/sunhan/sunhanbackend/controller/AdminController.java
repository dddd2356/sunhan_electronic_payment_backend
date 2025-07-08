package sunhan.sunhanbackend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sunhan.sunhanbackend.entity.UserEntity;
import sunhan.sunhanbackend.provider.JwtProvider;
import sunhan.sunhanbackend.respository.UserRepository;
import sunhan.sunhanbackend.service.UserService;

import java.util.List;

@RestController  // Controller 클래스에 @RestController 추가
//@RequiredArgsConstructor  // 생성자 주입을 위한 어노테이션 추가
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final UserService userService;  // UserService 주입
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;  // JwtProvider 변수 선언

    @Autowired
    public AdminController(UserService userService, UserRepository userRepository, JwtProvider jwtProvider) {
        this.userService = userService;
        this.userRepository = userRepository;  // 생성자 주입
        this.jwtProvider = jwtProvider;
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('2')")
    public ResponseEntity<List<UserEntity>> getAllUsers() {
        List<UserEntity> users = userService.findAllUsers();
        return ResponseEntity.ok(users);
    }
}