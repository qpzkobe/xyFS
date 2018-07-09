package xy.FileSystem.Controller;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Date;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import xy.FileSystem.Cache.UsesCache;
import xy.FileSystem.Entity.Diskfile;
import xy.FileSystem.Entity.DiskfileRepository;
import xy.FileSystem.Exception.StorageFileNotFoundException;
import xy.FileSystem.File.FileListener;
import xy.FileSystem.File.StoreSource;
import xy.FileSystem.File.UploadFileExt;
import xy.FileSystem.Propert.StorageProperties;
import xy.FileSystem.Service.FileSystemStorageService;
import xy.FileSystem.Service.QiniuService;

@Controller
public class FileUploadController {
	@Autowired
	private FileSystemStorageService storageService;
	@Autowired
	private StorageProperties prop;
	@Autowired
	private DiskfileRepository diskfileRepository;
	
	ExecutorService executorService = Executors.newFixedThreadPool(5);

	@GetMapping("/")
	public String listUploadedFiles(Model model) throws IOException {
		
		System.out.println("UsesCache.files:"+UsesCache.files);
		System.out.println("UsesCache.usedspace:"+UsesCache.usedspace);

		model.addAttribute("files",
				storageService.loadAll()
						.map(path -> MvcUriComponentsBuilder
								.fromMethodName(FileUploadController.class, "serveFile", path.getFileName().toString())
								.build().toString())
						.collect(Collectors.toList()));

		return "uploadForm";
	}

	@GetMapping("/files/{filename:.+}")
	@ResponseBody
	public ResponseEntity<Resource> serveFile(@PathVariable String filename) {

		Resource file = storageService.loadAsResource(filename);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFilename() + "\"")
				.body(file);
	}

	@PostMapping("/")
	public String handleFileUpload(MultipartHttpServletRequest request, RedirectAttributes redirectAttributes,
			@RequestParam int appid, @RequestParam String username, @RequestParam String groupid) {
		Iterator<String> itr = request.getFileNames();
		MultipartFile file = request.getFile(itr.next()); // 只取一个文件，不取多个
		String fileName = file.getOriginalFilename();

		if (prop.isRename()) {
			fileName = username + "_" + file.getOriginalFilename();
			if (groupid != null && !groupid.isEmpty()) {
				fileName = groupid + "_" + file.getOriginalFilename();
			}
		}

		final String finalFilename = fileName;
		final String finalFilePath = "";

		doUpload(file, finalFilename, finalFilePath);

		dbSave(appid, username, groupid, file, fileName);

		redirectAttributes.addFlashAttribute("message", "Successfully uploaded: " + file.getOriginalFilename());

		return "redirect:/";
	}

	public void doUpload(MultipartFile file, final String finalFilename, final String filePath) {

		// 磁盘存储
		if (prop.isTodisk()) {
			executorService.execute(new Runnable() {
				@Override
				public void run() {
					storageService.store(file, finalFilename);
				}
			});

		}
		
		//第三方存储
		UploadFileExt ufe;
		try {
			ufe = new UploadFileExt(finalFilename, file.getBytes(), file.getContentType(), file.getSize());
			if (ufe != null) {
				for (FileListener fl : StoreSource.getListensers()) {
										
					executorService.execute(new Runnable() {
						@Override
						public void run() {
							fl.Store(ufe);
						}
					});
				}
			}
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}

	public void dbSave(int appid, String username, String groupid, MultipartFile file, String fileName) {
		// 数据库存储
		Diskfile dbFile = new Diskfile();
		String fileId = UUID.randomUUID().toString();

		dbFile.setFileid(fileId);
		dbFile.setAppid(appid);
		dbFile.setFileExt(file.getContentType());
		dbFile.setFileFlag("1");
		dbFile.setFileName(fileName);
		dbFile.setFileSize(BigInteger.valueOf(file.getSize()));
		dbFile.setIspublic("1");
		dbFile.setUploadDate(new Date());
		dbFile.setUploadUser(username);
		dbFile.setUrldisk(prop.getDiskprefix() + fileName);

		// 更新缓存
		UsesCache.files++;
		UsesCache.usedspace = UsesCache.usedspace + file.getSize();

		if (prop.isToqiniu()) {
			dbFile.setUrlqiniu(prop.getQiniuprefix() + fileName);
		}

		diskfileRepository.save(dbFile);
	}

	@PostMapping("/upload")
	public String upload(MultipartHttpServletRequest request, RedirectAttributes redirectAttributes) {

		Iterator<String> itr = request.getFileNames();

		MultipartFile mpf = request.getFile(itr.next());

		storageService.store(mpf);
		redirectAttributes.addFlashAttribute("message", "Successfully uploaded: " + mpf.getOriginalFilename());

		return "redirect:/";

	}

	@ExceptionHandler(StorageFileNotFoundException.class)
	public ResponseEntity<?> handleStorageFileNotFound(StorageFileNotFoundException exc) {
		return ResponseEntity.notFound().build();
	}

}