package edp.davinci.server.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edp.davinci.server.dto.cronjob.CronJobTrack;
import edp.davinci.server.enums.CronJobStepEnum;
import edp.davinci.server.exception.ServerException;
import edp.davinci.server.util.CronJobTrackUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import edp.davinci.commons.util.CollectionUtils;
import edp.davinci.commons.util.JSONUtils;
import edp.davinci.commons.util.MD5Utils;
import edp.davinci.commons.util.StringUtils;
import edp.davinci.core.dao.entity.CronJob;
import edp.davinci.core.dao.entity.User;
import edp.davinci.server.component.quartz.ScheduleService;
import edp.davinci.server.component.screenshot.ImageContent;
import edp.davinci.server.dao.CronJobExtendMapper;
import edp.davinci.server.dao.UserExtendMapper;
import edp.davinci.server.dto.cronjob.CronJobConfig;
import edp.davinci.server.enums.CronJobMediaType;
import edp.davinci.server.util.FileUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service("weChatWorkScheduleService")
public class WeChatWorkScheduleServiceImpl extends BaseScheduleService implements ScheduleService {
    
    @Autowired
    private CronJobExtendMapper cronJobExtendMapper;

    @Autowired
    private UserExtendMapper userExtendMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Override
    public void execute(long jobId) throws Exception {
        
        CronJob cronJob = cronJobExtendMapper.selectByPrimaryKey(jobId);
	    CronJobTrack cronJobTrack = new CronJobTrack(cronJob);

	    if (null == cronJob || StringUtils.isEmpty(cronJob.getConfig())) {
            scheduleLogger.error("CronJob({}) config is empty", jobId);
		    CronJobTrackUtils.error(cronJobTrack, CronJobStepEnum.WECHAT_1_PARSE_CONFIG, "config is empty");
		    return;
        }
        
        cronJobExtendMapper.updateExecLog(jobId, "");
        CronJobConfig cronJobConfig = null;
        try {
            cronJobConfig = JSONUtils.toObject(cronJob.getConfig(), CronJobConfig.class);
        } catch (Exception e) {
            scheduleLogger.error("Cronjob({}) parse config({}) error:{}", jobId, cronJob.getConfig(), e.getMessage());
	        CronJobTrackUtils.getBuilder().appendParam("config", cronJob.getConfig()).appendParam("error", e.toString())
			        .error(cronJobTrack, CronJobStepEnum.WECHAT_1_PARSE_CONFIG, "parse config error");
            return;
        }

        if (StringUtils.isEmpty(cronJobConfig.getType())) {
            scheduleLogger.error("Cronjob({}) config type is empty", jobId);
	        CronJobTrackUtils.error(cronJobTrack, CronJobStepEnum.WECHAT_1_PARSE_CONFIG, "config type is empty");
            return;
        }

        scheduleLogger.info("CronJob({}) is start! --------------", jobId);
	    CronJobTrackUtils.info(cronJobTrack, CronJobStepEnum.WECHAT_1_PARSE_CONFIG, "parse config is finish");

        List<ImageContent> images = null;
        User creater = userExtendMapper.selectByPrimaryKey(cronJob.getCreateBy());

        if (cronJobConfig.getType().equals(CronJobMediaType.IMAGE.getType())) {
            images = generateImages(jobId, cronJobConfig, creater.getId(), cronJobTrack);
        }

        if (CollectionUtils.isEmpty(images)) {
            scheduleLogger.warn("CronJob({}) image is empty", jobId);
	        CronJobTrackUtils.error(cronJobTrack, CronJobStepEnum.WECHAT_4_SEND, "wechat send content is empty");
	        return;
        }

        String url = cronJobConfig.getWebHookUrl();

        for (ImageContent imageContent : images)  {
            if (null == imageContent) {
                log.error("CronJob({}) image is null !", cronJob.getId());
                return;
            }
            File imageContentFile = imageContent.getImageFile();
            // 将大于2M的图片进行压缩
            if (imageContentFile.length() > (2 * 1024 * 1024)) {
                scheduleLogger.info("Image size must be less than 2M, the size is {}!", imageContentFile.length());

                scheduleLogger.info("Image start to compressed!", imageContentFile.getPath());
                File file = FileUtils.compressedImage(imageContent.getImageFile().getPath());

                scheduleLogger.info("Image compressed successfully! the size is {}.", file.length());
                imageContent.setImageFile(file);

                scheduleLogger.info("The original image has been replaced with a new image(path:{})!", imageContentFile.getPath());
            }
            scheduleLogger.info("CronJob({}) is ready to request WeChatWork API", cronJob.getId());

            Map<String, String> mbMap = getMD5AndBase64(imageContent.getImageFile());

            Map<String, Object> weChatWorkMap = new HashMap<>();
            weChatWorkMap.put("msgtype", "image");
            weChatWorkMap.put("image", mbMap);

	        try {
		        ResponseEntity responseEntity =restTemplate.postForEntity(url, weChatWorkMap, null);
		        if (!responseEntity.getStatusCode().is2xxSuccessful()) {
			        throw new ServerException("request wechat api fail");
		        }
	        } catch (Exception e) {
		        CronJobTrackUtils.getBuilder().appendParam("errro", e.toString())
				        .error(cronJobTrack, CronJobStepEnum.WECHAT_4_SEND, "wechat send fail");
	        }
            scheduleLogger.info("CronJob({}) is success to request WeChatWork API", cronJob.getId());
        }
        scheduleLogger.info("CronJob({}) is finish! --------------", jobId);
	    CronJobTrackUtils.info(cronJobTrack, CronJobStepEnum.WECHAT_4_SEND, "CronJob is finish!");
    }

    /**
     * 根据图片地址获取MD5和Base64
     *
     * @param file 图片
     * @return
     */
    private static Map<String, String> getMD5AndBase64(File file) {
        Map<String, String> resMap = new HashMap<>();
        try (InputStream in = new FileInputStream(file);
                ByteArrayOutputStream bytesOut = new ByteArrayOutputStream((int) file.length());) {

            byte[] buf = new byte[1024];
            int len = -1;
            while ((len = in.read(buf)) != -1) {
                bytesOut.write(buf, 0, len);
            }

            bytesOut.flush();

            // 图片内容的base64编码
            String base64 = encode(bytesOut.toByteArray());
            resMap.put("base64", base64);

            // 图片内容（base64编码前）的md5值
            MessageDigest md = MessageDigest.getInstance("md5");
            md.update(bytesOut.toByteArray());
            resMap.put("md5", MD5Utils.byteToString(md.digest()));
            
            return resMap;
        
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

}