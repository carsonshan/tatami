package fr.ippon.tatami.service;

import fr.ippon.tatami.domain.DigestType;
import fr.ippon.tatami.domain.User;
import fr.ippon.tatami.repository.*;
import fr.ippon.tatami.repository.search.UserSearchRepository;
import fr.ippon.tatami.security.AuthoritiesConstants;
import fr.ippon.tatami.security.SecurityUtils;
import fr.ippon.tatami.service.util.DomainUtil;
import fr.ippon.tatami.service.util.RandomUtil;
import fr.ippon.tatami.web.rest.dto.UserDTO;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import javax.validation.ConstraintViolationException;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Service class for managing users.
 */
@Service
public class UserService {

    private final Logger log = LoggerFactory.getLogger(UserService.class);

    @Inject
    private PasswordEncoder passwordEncoder;

    @Inject
    private FriendRepository friendRepository;

    @Inject
    private FollowerRepository followerRepository;

    @Inject
    private UserRepository userRepository;

    @Inject
    private CounterRepository counterRepository;

    @Inject
    private SearchService searchService;

    @Inject
    private UserSearchRepository userSearchRepository;

    @Inject
    private RssUidRepository rssUidRepository;

    @Inject
    private MailDigestRepository mailDigestRepository;

    @Inject
    private BlockService blockService;

    public Optional<User> getUser(String email) {
        return userRepository.findOneByEmail(email);
    }

    public Optional<User> getCurrentUser() {
        return userRepository.findOneByEmail(SecurityUtils.getCurrentUserEmail());
    }

    public Optional<User> activateRegistration(String key) {
        log.debug("Activating user for activation key {}", key);
        userRepository.findOneByActivationKey(key)
            .map(user -> {
                // activate given user for the registration key.
                user.setActivated(true);
                user.setActivationKey(null);
                userRepository.save(user);
                log.debug("Activated user: {}", user);
                return user;
            });
        return Optional.empty();
    }

    public Optional<User> completePasswordReset(String newPassword, String key) {
        log.debug("Reset user password for reset key {}", key);

        return userRepository.findOneByResetKey(key)
            .filter(user -> {
                ZonedDateTime oneDayAgo = ZonedDateTime.now().minusHours(24);
                return user.getResetDate().after(Date.from(oneDayAgo.toInstant()));
            })
            .map(user -> {
                user.setPassword(passwordEncoder.encode(newPassword));
                user.setResetKey(null);
                user.setResetDate(null);
                userRepository.save(user);
                return user;
            });
    }

    public Optional<User> requestPasswordReset(String mail) {
        return userRepository.findOneByEmail(mail)
            .filter(User::getActivated)
            .map(user -> {
                user.setResetKey(RandomUtil.generateResetKey());
                user.setResetDate(new Date());
                userRepository.save(user);
                return user;
            });
    }

    public User createUserInformation(String username, String password, String firstName, String lastName, String email,
                                      String langKey, String jobTitle, String jobDescription, String phoneNumber, boolean mentionEmail, String rssUid, boolean weeklyDigest, boolean dailyDigest, String domain) {

        User newUser = new User();
        newUser.setId(UUID.randomUUID().toString());
        Set<String> authorities = new HashSet<>();
        String encryptedPassword = passwordEncoder.encode(password);
        newUser.setUsername(username);
        // new user gets initially a generated password
        newUser.setPassword(encryptedPassword);
        newUser.setFirstName(firstName);
        newUser.setLastName(lastName);
        newUser.setEmail(email);
        newUser.setLangKey(langKey);
        newUser.setJobTitle(jobTitle);
        newUser.setJobDescription(jobDescription);
        newUser.setPhoneNumber(phoneNumber);
        newUser.setMentionEmail(mentionEmail);
        newUser.setRssUid(rssUid);
        newUser.setWeeklyDigest(weeklyDigest);
        newUser.setDailyDigest(dailyDigest);
        newUser.setDomain(domain);
        // new user is not active
        newUser.setActivated(false);
        // new user gets registration key
        newUser.setActivationKey(RandomUtil.generateActivationKey());
        authorities.add(AuthoritiesConstants.USER);
        newUser.setAuthorities(authorities);
        userRepository.save(newUser);
        searchService.addUser(newUser);
        log.debug("Created Information for User: {}", newUser);
        return newUser;
    }

    public User createUser(UserDTO userDTO) {
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        String username = DomainUtil.getUsernameFromEmail(userDTO.getEmail());

        user.setUsername(username);
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        user.setJobTitle(userDTO.getJobTitle());
        user.setJobDescription(userDTO.getJobDescription());
        if (userDTO.getLangKey() == null) {
            user.setLangKey("en"); // default language is English
        } else {
            user.setLangKey(userDTO.getLangKey());
        }
        String domain = DomainUtil.getDomainFromEmail(userDTO.getEmail());
        user.setDomain(domain);
        user.setAuthorities(userDTO.getAuthorities());
        String encryptedPassword = passwordEncoder.encode(RandomUtil.generatePassword());
        user.setPassword(encryptedPassword);
        user.setResetKey(RandomUtil.generateResetKey());
        user.setResetDate(new Date());
        user.setActivated(true);
        user.setDailyDigest(false);
        user.setWeeklyDigest(false);
        user.setMentionEmail(false);
        counterRepository.createStatusCounter(user.getEmail());
        counterRepository.createFollowersCounter(user.getEmail());
        counterRepository.createFriendsCounter(user.getEmail());
        userRepository.save(user);
        searchService.addUser(user);
        log.debug("Created Information for User: {}", user);
        return user;
    }

    public void updateUserInformation(String firstName, String lastName, String email, String langKey, String jobTitle,
                                      String jobDescription, String phoneNumber) {
        getCurrentUser().ifPresent(u -> {
            u.setFirstName(firstName);
            u.setLastName(lastName);
            u.setEmail(email);
            u.setDomain(DomainUtil.getDomainFromEmail(email));
            u.setLangKey(langKey);
            u.setJobTitle(jobTitle);
            u.setJobDescription(StringUtils.replace(jobDescription, "\n", "<br>"));
            u.setPhoneNumber(phoneNumber);
            userRepository.save(u);
            searchService.removeUser(u);
            searchService.addUser(u);
            log.debug("Changed Information for User: {}", u);
        });
    }

    public void updateUserPreferences(boolean mentionEmail, String rssUid,
                                      boolean weeklyDigest, boolean dailyDigest) {
        getCurrentUser().ifPresent(u -> {
            u.setMentionEmail(mentionEmail);
            u.setRssUid(rssUid);
            u.setWeeklyDigest(weeklyDigest);
            u.setDailyDigest(dailyDigest);
            userRepository.save(u);
            log.debug("Change Preferences for User: {}", u);
        });
    }

    public void deleteUserInformation(String email) {
        userRepository.findOneByEmail(email).ifPresent(u -> {
            userRepository.delete(u);
            searchService.removeUser(u);
            log.debug("Deleted User: {}", u);
        });
    }

    public void changePassword(String password) {
        getCurrentUser().ifPresent(u -> {
            String encryptedPassword = passwordEncoder.encode(password);
            u.setPassword(encryptedPassword);
            userRepository.save(u);
            log.debug("Changed password for User: {}", u);
        });
    }

    public boolean isAdmin(String email) {
        Optional<User> userOptional = getUser(email);
        return userOptional.isPresent() && userOptional.get().getAuthorities().contains(AuthoritiesConstants.ADMIN);
    }

    /**
     * Activate of de-activate rss publication for the timeline.
     *
     * @return the rssUid used for rss publication, empty if no publication
     */
    public String updateRssTimelinePreferences(boolean booleanPreferencesRssTimeline) {

        User currentUser = getCurrentUser().get();
        String rssUid = currentUser.getRssUid();
        if (booleanPreferencesRssTimeline) {
            // if we already have an rssUid it means it's already activated :
            // nothing to do, we do not want to change it

            if ((rssUid == null) || rssUid.equals("")) {
                // Activate rss feed publication.
                rssUid = rssUidRepository.generateRssUid(currentUser.getUsername());
                currentUser.setRssUid(rssUid);
                log.debug("Updating rss timeline preferences : rssUid={}", rssUid);

                try {
                    userRepository.save(currentUser);
                } catch (ConstraintViolationException cve) {
                    log.info("Constraint violated while updating preferences : " + cve);
                    throw cve;
                }
            }

        } else {

            // Remove current rssUid from both CF!
            if ((rssUid != null) && (!rssUid.isEmpty())) {
                // this used to delete from a rss table. now we don't have one.
                rssUidRepository.removeRssUid(rssUid);
                rssUid = "";
                currentUser.setRssUid(rssUid);
                log.debug("Updating rss timeline preferences : rssUid={}", rssUid);

                try {
                    userRepository.save(currentUser);
                } catch (ConstraintViolationException cve) {
                    log.info("Constraint violated while updating preferences : " + cve);
                    throw cve;
                }
            }
        }
        return rssUid;
    }

    /**
     * update registration to weekly digest email.
     */
    public void updateWeeklyDigestRegistration(boolean registration) {
        User currentUser = getCurrentUser().get();
        currentUser.setWeeklyDigest(registration);
        String day = String.valueOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK));

        if (registration) {
            mailDigestRepository.subscribeToDigest(DigestType.WEEKLY_DIGEST, currentUser.getUsername(),
                currentUser.getDomain(), day);
        } else {
            mailDigestRepository.unsubscribeFromDigest(DigestType.WEEKLY_DIGEST, currentUser.getUsername(),
                currentUser.getDomain(), day);
        }

        log.debug("Updating weekly digest preferences : " +
            "weeklyDigest={} for user {}", registration, currentUser.getUsername());
        try {
            userRepository.save(currentUser);
            userSearchRepository.index(currentUser);
//            userRepository.updateUser(currentUser);
        } catch (ConstraintViolationException cve) {
            log.info("Constraint violated while updating preferences : " + cve);
            throw cve;
        }
    }

    /**
     * Update registration to daily digest email.
     */
    public void updateDailyDigestRegistration(boolean registration) {
        User currentUser = getCurrentUser().get();
        currentUser.setDailyDigest(registration);
        String day = String.valueOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK));

        if (registration) {
            mailDigestRepository.subscribeToDigest(DigestType.DAILY_DIGEST, currentUser.getUsername(),
                currentUser.getDomain(), day);
        } else {
            mailDigestRepository.unsubscribeFromDigest(DigestType.DAILY_DIGEST, currentUser.getUsername(),
                currentUser.getDomain(), day);
        }

        log.debug("Updating daily digest preferences : dailyDigest={} for user {}", registration, currentUser.getUsername());
        try {
            userRepository.save(currentUser);
        } catch (ConstraintViolationException cve) {
            log.info("Constraint violated while updating preferences : " + cve);
            throw cve;
        }
    }

    /**
     * Return a collection of Users based on their email addresses (ie : uid)
     *
     * @param emails the collection : must not be null
     * @return a Collection of User
     */
    public Collection<User> getUsersByEmail(Collection<String> emails) {
        final Collection<User> users = new ArrayList<User>();
        Optional<User> user;
        for (String email : emails) {
            user = userRepository.findOneByEmail(email);
            user.ifPresent(users::add);
        }
        return users;
    }

    public Collection<UserDTO> buildUserDTOList(Collection<User> users) {
        Collection<UserDTO> userDTOs = new ArrayList<UserDTO>();
        for (User user : users) {
            UserDTO userDTO = buildUserDTO(user);
            userDTOs.add(userDTO);
        }
        return userDTOs;
    }

    public UserDTO buildUserDTO(User user) {
        String currentUserEmail = SecurityUtils.getCurrentUserEmail();
        UserDTO userDTO = getUserDTOFromUser(user);
        userDTO.setYou(user.getEmail().equals(currentUserEmail));
        userDTO.setId(user.getId());
        if (!userDTO.isYou()) {
            Collection<String> currentFriendLogins = friendRepository.findFriendsForUser(currentUserEmail);
            Collection<String> currentFollowersLogins = followerRepository.findFollowersForUser(currentUserEmail);
            Collection<String> currentBlockedUsers = blockService.getUsersBlockedEmailForUser(currentUserEmail);
            userDTO.setFriend(currentFriendLogins.contains(user.getEmail()));
            userDTO.setFollower(currentFollowersLogins.contains(user.getEmail()));
            userDTO.setBlocked(currentBlockedUsers.contains(user.getEmail()));
        }
        return userDTO;
    }

    private UserDTO getUserDTOFromUser(User user) {
        UserDTO dto = new UserDTO();
        dto.setEmail(user.getEmail());
        dto.setUsername(user.getUsername());
        dto.setAvatar(user.getAvatar());
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        dto.setJobTitle(user.getJobTitle());
        dto.setJobDescription(user.getJobDescription());
        dto.setPhoneNumber(user.getPhoneNumber());
        dto.setAttachmentsSize(user.getAttachmentsSize());
        dto.setStatusCount(user.getStatusCount());
        dto.setFriendsCount(user.getFriendsCount());
        dto.setFollowersCount(user.getFollowersCount());
        dto.setActivated(user.getActivated());
        return dto;
    }
}
