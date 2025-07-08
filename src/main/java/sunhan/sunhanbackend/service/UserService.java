package sunhan.sunhanbackend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import sunhan.sunhanbackend.entity.UserEntity;
import sunhan.sunhanbackend.respository.UserRepository;

import java.util.List;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;

    @Autowired
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
    private PasswordEncoder passwdEncoder = new BCryptPasswordEncoder();

    public UserEntity getUserInfo(String userId) {
        UserEntity user = userRepository.findByUserId(userId);
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        // 직원 저장 후 반환
        return userRepository.save(user);
    }

    // 모든 유저 조회 메서드
    public List<UserEntity> findAllUsers() {
        return userRepository.findAll();
    }

//    * 다른 DB에서 사용자 정보를 가져와서 새로 생성하는 메서드
//     * password는 userId와 동일하게 설정 (평문)
//     */
    public UserEntity createUserFromExternalDB(String userId, String userName, String jobLevel, String dept) {
        // 이미 존재하는 사용자인지 확인
        UserEntity existingUser = userRepository.findByUserId(userId);
        if (existingUser != null) {
            return existingUser;
        }

        // 새 사용자 생성
        UserEntity newUser = new UserEntity();
        newUser.setUserId(userId);
        newUser.setUserName(userName);
        newUser.setPasswd(userId); // 디폴트 비밀번호 = 사용자 ID (평문)
        newUser.setJobLevel(jobLevel);
        newUser.setDeptCode(dept);

        return userRepository.save(newUser);
    }
    /**
     * 사용자가 최초 로그인 후 비밀번호를 변경할 때 사용
     * 새 비밀번호는 BCrypt로 암호화
     */
    public void updateUserPasswd(String userId, String newPasswd) {
        UserEntity user = userRepository.findByUserId(userId);
        if (user != null) {
            String encodedPasswd = passwdEncoder.encode(newPasswd);
            user.setPasswd(encodedPasswd);
            userRepository.save(user);
        }
    }
    /**
     * 디폴트 비밀번호(평문)를 사용하는 사용자인지 확인
     */
    public boolean isUsingDefaultPasswd(String userId) {
        UserEntity user = userRepository.findByUserId(userId);
        if (user != null) {
            String passwd = user.getPasswd();
            // BCrypt 해시가 아니고, 비밀번호가 사용자 ID와 같으면 디폴트 비밀번호
            return !passwd.startsWith("$2a$") && !passwd.startsWith("$2b$") && passwd.equals(userId);
        }
        return false;
    }

    public String getUserRole(String userId) {
        // 카카오 ID로 사용자 정보 확인
        UserEntity user = userRepository.findByUserId(userId);

        if (user != null) {
            return user.getJobLevel();
        } else {
            return "0"; // 기본 role 설정
        }
    }
}
