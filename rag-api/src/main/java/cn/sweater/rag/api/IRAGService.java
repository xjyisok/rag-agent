package cn.sweater.rag.api;

import cn.sweater.rag.api.response.Response;
import org.springframework.web.multipart.MultipartFile;
import org.stringtemplate.v4.ST;

import java.util.List;

public interface IRAGService {

    Response<List<String>> queryRagTagList();

    Response<String> uploadFile(String ragTag, List<MultipartFile> files);

    Response<String> analyzeGitRepository(String repoUrl, String userName, String token, String branchName) throws Exception;



}
