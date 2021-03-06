package com.coco52.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.coco52.entity.*;
import com.coco52.entity.RespResult;
import com.coco52.enums.ResultCode;
import com.coco52.mapper.blog.AccountMapper;
import com.coco52.mapper.blog.RoleAccountMapper;
import com.coco52.mapper.blog.UserMapper;
import com.coco52.service.UserService;
import com.coco52.util.FileUtils;
import com.coco52.util.JwtTokenUtil;
import com.coco52.util.RequestUtils;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.DefaultPutRet;
import com.qiniu.util.Auth;
import org.apache.tomcat.util.http.fileupload.FileUploadException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;

@Service
@DS("master")
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final AccountMapper accountMapper;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    private final RoleAccountMapper roleAccountMapper;
    private final JwtTokenUtil jwtTokenUtil;

    private final RequestUtils requestUtils;

    @Value("${jwt.tokenHead}")
    private String tokenHead;
    @Value("${jwt.tokenHeader}")
    private String tokenHeader;

    public UserServiceImpl(UserMapper userMapper, AccountMapper accountMapper, UserDetailsService userDetailsService, PasswordEncoder passwordEncoder, RoleAccountMapper roleAccountMapper, JwtTokenUtil jwtTokenUtil, RequestUtils requestUtils) {
        this.userMapper = userMapper;
        this.accountMapper = accountMapper;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.roleAccountMapper = roleAccountMapper;
        this.jwtTokenUtil = jwtTokenUtil;
        this.requestUtils = requestUtils;
    }

    /**
     * ????????????  ????????????????????????  user??? account???
     * user???????????????????????????
     * account ???????????????????????????
     *
     * @param registerUser ?????????????????????????????????
     * @return 1????????????  0????????????  2??????????????????
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public RespResult registerUser(Account registerUser) {
        QueryWrapper<Account> wrapper = new QueryWrapper<>();
        wrapper.eq("username", registerUser.getUsername());
        Account account = accountMapper.selectOne(wrapper);
        if (account != null) {
            return RespResult.fail(ResultCode.USER_HAS_BEEN_REGISTERED,null);
        }
        registerUser.setUuid(UUID.randomUUID().toString().replace("-", "").toLowerCase());
        registerUser.setPassword(passwordEncoder.encode(registerUser.getPassword()));
        int flag = accountMapper.insert(registerUser);
        MyUser myUser = new MyUser();
        myUser.setUuid(registerUser.getUuid());
        myUser.setState(0);
        myUser.setValidateCode(UUID.randomUUID().toString().replace("-", "").toLowerCase());
        userMapper.insert(myUser);
        roleAccountMapper.insert(new RoleAccount(null, registerUser.getUuid(), 3));
        if (flag == 1) {
            return RespResult.success(ResultCode.USER_REGISTER_SUCCESS,null);
        } else {
            return RespResult.success(ResultCode.USER_UNKNOWN_ERROR,null);
        }
    }

    /**
     * ????????????
     *
     * @param loginAccount ???????????????????????????
     * @return LoginMsgVO?????????   ??????code???   0   1  2
     * 0  ??????????????????
     * 1  ??????????????????
     * 2  ??????????????????
     */
    @SuppressWarnings("AlibabaCollectionInitShouldAssignCapacity")
    @Override
    public RespResult login(Account loginAccount) {
        if (loginAccount == null || StringUtils.isEmpty(loginAccount.getUsername()) || StringUtils.isEmpty(loginAccount.getPassword())) {
            return RespResult.fail("????????????????????????~");
        }
        UserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(loginAccount.getUsername());
        } catch (UsernameNotFoundException e) {

            return new RespResult(400, e.getMessage(), null);
        }
        if (userDetails == null || !passwordEncoder.matches(loginAccount.getPassword(), userDetails.getPassword())) {
            return RespResult.fail(ResultCode.USERNAME_PASSWORD_ERROR,null);
        } else if (!userDetails.isEnabled()) {
            return RespResult.fail(ResultCode.USER_ACCOUNT_NOT_EXIST,null);
        } else if (!userDetails.isAccountNonLocked()) {
            return RespResult.fail(ResultCode.USER_ACCOUNT_HAS_BEEN_BAN,null);
        } else if (!userDetails.isCredentialsNonExpired()) {
            return RespResult.fail(ResultCode.USER_PASSWORD_HAS_EXPIRED,null);
        } else if (!userDetails.isAccountNonExpired()) {
            return RespResult.fail(ResultCode.USER_ACCOUNT_HAS_EXPIRED,null);
        }
        //??????security??????????????????
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
//        ????????????????????????????????????
        //???????????????????????????user
        MyUser user = userMapper.selectUsersByUsername(loginAccount.getUsername());

        String token = jwtTokenUtil.generateToken(userDetails, user);

        LocalDateTime localDateTime = LocalDateTime.now();

        //?????????????????????????????????
        MyUser updateUser = new MyUser();
        updateUser.setUpdateTime(localDateTime);
        updateUser.setLastLoginTime(localDateTime);
        userMapper.update(updateUser, new QueryWrapper<MyUser>().eq("uuid", user.getUuid()));

        //noinspection AlibabaCollectionInitShouldAssignCapacity
        Map<String, String> tokenMap = new HashMap<>();
        tokenMap.put("token", token);
        tokenMap.put("tokenHead", tokenHead);
        tokenMap.put("username", loginAccount.getUsername());
        tokenMap.put("uuid", user.getUuid());
        return RespResult.success(ResultCode.USER_LOGIN_SUCCESS, tokenMap);
    }


    /**
     * ????????????
     * @param loginAccount ?????? ??????
     * @return null???????????????????????????  MyUser?????????????????????
     */
    @Override
    public MyUser selectByAccount(Account loginAccount) {
        QueryWrapper<Account> accountWrapper = new QueryWrapper<>();
        accountWrapper.eq("username", loginAccount.getUsername());
        accountWrapper.eq("password", loginAccount.getPassword());
        Account account = accountMapper.selectOne(accountWrapper);
        if (account == null) {
            return null;
        }
        QueryWrapper<MyUser> userWrapper = new QueryWrapper<>();
        userWrapper.eq("uuid", account.getUuid());

        return userMapper.selectOne(userWrapper);
    }
    /**
     * ?????????????????????Account
     */
    @Override
    public Account selectByUsername(String username) {
        QueryWrapper<Account> accountWrapper = new QueryWrapper<>();
        accountWrapper.eq("username", username);
        return accountMapper.selectOne(accountWrapper);
    }

    /**
     * ????????????-> ???????????? lock ??????true
     */
    @Override
    public RespResult banUser(MyUser myUser) {
        if (ObjectUtils.isEmpty(myUser.getUuid())) {
            return RespResult.fail(ResultCode.USER_UUID_iS_EMPTY,null);
        }
        UpdateWrapper<MyUser> myUserUpdateWrapper = new UpdateWrapper<>();
        myUserUpdateWrapper.eq("uuid", myUser.getUuid());
//        myUserUpdateWrapper.set("is_lock", 1);
        MyUser myUser1 = new MyUser();
        myUser1.setIsLock(true);
        int update = userMapper.update(myUser1, myUserUpdateWrapper);
        return update == 1 ? RespResult.success(ResultCode.USER_BAN_SUCCESS, myUser.getUuid()) : RespResult.fail(ResultCode.USER_BAN_FAIL, myUser.getUuid());
    }

    /**
     * ????????????->????????? ???available ?????????true ???????????????
     *
     * @param myUser ????????????  ????????????uuid????????????
     */
    @Override
    public RespResult delUser(MyUser myUser) {
        if (ObjectUtils.isEmpty(myUser.getUuid())) {
            return RespResult.fail(ResultCode.USER_UUID_iS_EMPTY,null);
        }
        UpdateWrapper<MyUser> myUserUpdateWrapper = new UpdateWrapper<>();
        myUserUpdateWrapper.eq("uuid", myUser.getUuid());
        myUserUpdateWrapper.set("is_available", true);
        int update = userMapper.update(null, myUserUpdateWrapper);
        return update == 1 ? RespResult.success("?????????????????????", myUser.getUuid()) : RespResult.fail("??????????????????,????????????????????????", myUser.getUuid());
    }

    /**
     * ??????uuid ??????????????????
     */
    @Override
    public RespResult updateUser(MyUser myUser,HttpServletRequest request) {
        String allToken = request.getHeader(tokenHeader);
        String token = allToken.substring(tokenHead.length());
        String uuid = jwtTokenUtil.getUUIDFromToken(token);
        if (ObjectUtils.isEmpty(uuid)){
            return RespResult.fail(ResultCode.TOKEN_NOT_EXIST.getCode(),ResultCode.TOKEN_NOT_EXIST.getMessage(), null);
        }
        UpdateWrapper<MyUser> myUserUpdateWrapper = new UpdateWrapper<>();
        myUserUpdateWrapper.eq("uuid", uuid);
        int update = userMapper.update(myUser, myUserUpdateWrapper);
        return update == 1 ? RespResult.success(ResultCode.USER_PROFILE_UPDATED_SUCCESSFULLY, myUser.getUuid()) : RespResult.fail(ResultCode.USER_PROFILE_UPDATED_FAIL, myUser.getUuid());
    }

    @Override
    public RespResult showUser(HttpServletRequest request) {
        String uuid = requestUtils.getUUIDFromRequest(request);
        QueryWrapper<MyUser> wrapper = new QueryWrapper<>();
        wrapper.eq("uuid",uuid);
        MyUser myUsers = userMapper.selectOne(wrapper);
        myUsers.setId(null);
        myUsers.setIsLock(null);
        myUsers.setValidateCode(null);
        myUsers.setUuid(null);
        myUsers.setUpdateTime(null);
        myUsers.setState(null);
        myUsers.setIsExpires(null);
        myUsers.setIsAvailable(null);
        if (ObjectUtils.isEmpty(myUsers.getAge())){
            myUsers.setAge(0);
        }
        return RespResult.success(ResultCode.SUCCESS, myUsers);
    }

    @Override
    public RespResult uploadFile(MultipartFile[] files) {
        List<JSONObject> list = new ArrayList<>();


        for (MultipartFile file : files) {
            //???????????????
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("state", false);
            //????????????????????????
            String originalFilename = file.getOriginalFilename();
            jsonObject.put("fileName", originalFilename);
            String realName = null;
            try {
                //????????????
                realName = FileUtils.saveFile(file);
            } catch (FileNotFoundException | FileUploadException e) {
                //???????????????????????????
                jsonObject.put("exception", e.getMessage());
                //                return RespResult.fail(ResultCode.FILE_UPLOAD_ERROR.getCode(), ResultCode.FILE_UPLOAD_ERROR.getMessage(), null);
            } // ??????????????????????????? ???????????????
            //                return RespResult.fail(ResultCode.FILE_UPLOAD_ERROR.getCode(), e.getMessage(), null);
            finally {
                if (jsonObject.containsKey("exception")) {
                    list.add(jsonObject);
                } else {
                    jsonObject.put("state", true);
                    jsonObject.put("realName", realName);
                    //????????????????????????
                    list.add(jsonObject);
                }
            }
        }
        int successNum = 0;
        for (JSONObject jsonObject : list) {
            boolean state = (boolean) jsonObject.get("state");
            if (state) {
                successNum += 1;
            }
        }
        if (successNum == 0) {
            return RespResult.fail(ResultCode.FILE_UPLOAD_ERROR, null);
        } else if (successNum > 0 && successNum < files.length) {
            return RespResult.success(ResultCode.FILE_PART_UPLOAD_SUCCESS, list);
        } else {
            return RespResult.success(ResultCode.File_UPLOAD_SUCCESS, list);
        }
    }

    /**
     * ????????????  ??????Header???token??????uuid ????????????
     *
     */
    @Override
    public RespResult setAvatar(HttpServletRequest request, MultipartFile avatar) {
        String uuid = requestUtils.getUUIDFromRequest(request);
        String realName;
        try {
            realName = FileUtils.saveFile(avatar);
        } catch (Exception e) {
            return RespResult.fail(ResultCode.USER_AVATAR_UPLOAD_ERROR, null);
        }
        //   /2021/09/24/asdasdasdasd.png
        UpdateWrapper<MyUser> wrapper = new UpdateWrapper<>();
        wrapper.eq("uuid", uuid);
        MyUser myUser = new MyUser();
        myUser.setAvatar(realName);
        int update = userMapper.update(myUser, wrapper);
        return update == 1 ?
                RespResult.success(ResultCode.USER_AVATAR_UPLOAD_SUCCESS, null) :
                RespResult.fail(ResultCode.USER_AVATAR_UPLOAD_ERROR, null);

    }

    /**
     * ????????????  ??????Header???token??????uuid ????????????
     *
     */
    @Override
    public RespResult setAvatar2(HttpServletRequest request, MultipartFile avatar) {
        String uuid = requestUtils.getUUIDFromRequest(request);
        //????????????????????? Region ??????????????????
        Configuration cfg = new Configuration(Region.region1());
        //...???????????????????????????
        UploadManager uploadManager = new UploadManager(cfg);
        //...???????????????????????????????????????
        String accessKey = "ZCorfCyvLFPyIBFSD306B-KwllQyh7A6G6C5s47o";
        String secretKey = "CAB-uxqvQUPGZyJFkiLa59MIOSrF-7lBFYuzqhdY";
        String bucket = "82coco";
        String key= "avatar/"+FileUtils.getMd5(avatar);
        try {

            Auth auth = Auth.create(accessKey, secretKey);
            String upToken = auth.uploadToken(bucket);
            try {
                Response response = uploadManager.put(avatar.getInputStream(),key,upToken,null, null);
                //???????????????????????????
//                DefaultPutRet putRet = new Gson().fromJson(response.bodyString(), DefaultPutRet.class);
                DefaultPutRet putRet = JSONObject.parseObject(response.bodyString(), DefaultPutRet.class);
                System.out.println(putRet.key);
                System.out.println(putRet.hash);
                UpdateWrapper<MyUser> wrapper = new UpdateWrapper<>();
                wrapper.eq("uuid", uuid);
                MyUser myUser = new MyUser();
                myUser.setAvatar(putRet.key);
                int update = userMapper.update(myUser, wrapper);
                return update == 1 ?
                        RespResult.success(ResultCode.USER_AVATAR_UPLOAD_SUCCESS, null) :
                        RespResult.fail(ResultCode.USER_AVATAR_UPLOAD_ERROR, null);

            } catch (QiniuException ex) {
                Response r = ex.response;
                System.err.println(r.toString());
                return RespResult.fail(ResultCode.USER_AVATAR_UPLOAD_ERROR, r.toString());
            }
        } catch (IOException e) {
            return RespResult.fail(ResultCode.USER_AVATAR_UPLOAD_ERROR, e.getMessage());
        }
    }

    @Override
    public BufferedImage getAvatar(String uuid) {
        QueryWrapper<MyUser> wrapper = new QueryWrapper<>();
        wrapper.eq("uuid", uuid);
        MyUser myUser = userMapper.selectOne(wrapper);
        if (ObjectUtils.isEmpty(myUser)) {
            return null;
        }
        if (myUser.getAvatar() == null) {
            return null;
        }
//        \2021\09\24\ed729425-4afb-42f1-b561-549aef3b98aa.png
        File file =new File(FileUtils.UPLOAD_DIR+myUser.getAvatar());
        if (!file.exists()){
            return null;
        }

        BufferedImage bufferedImage;
        try {
            bufferedImage=ImageIO.read(file);
        } catch (IOException e) {
            return null;
        }
        return bufferedImage;
    }
}
