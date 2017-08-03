package wyc.commands;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import wybs.util.StdProject;
import wyc.util.AbstractProjectCommand;
import wycc.lang.ConfigFile;
import wycc.util.Logger;
import wyfs.lang.Content;
import wyfs.lang.Path;
import wyfs.util.DirectoryRoot;
import wyfs.util.Trie;
import wyfs.lang.Content.Registry;

public class Build extends AbstractProjectCommand<Build.Result> {
	/**
	 * Result kind for this command
	 *
	 */
	public enum Result {
		SUCCESS,
		ERRORS,
		INTERNAL_FAILURE
	}

	private DirectoryRoot rootdir;

	private static final String REGISTRY_DIR = System.getProperty("user.home")
			+ "/.wy/registry".replaceAll("/", File.separator);
	private static final Path.ID BUILD_FILE = Trie.fromString("wy");

	public Build(Content.Registry registry, Logger logger) {
		super(registry,logger);
	}

	@Override
	public String getName() {
		return "build";
	}

	@Override
	public String getDescription() {
		return "Build the enclosing project";
	}

	@Override
	protected void finaliseConfiguration() throws IOException {
		super.finaliseConfiguration();
		rootdir = getDirectoryRoot(rootdir,new DirectoryRoot(".",registry));
	}

	@Override
	public Result execute(String... args) {
		try {
			// Finalise the configuration before continuing.
			StdProject project = initialiseProject();
			//
			Path.Entry<ConfigFile> entry = rootdir.get(BUILD_FILE, ConfigFile.ContentType);
			if(entry == null) {
				// Couldn't locate the build file
				System.err.println("ERROR: cannot find build file \"wy.toml\"");
				return Result.ERRORS;
			} else {
				Map<String,Object> config = entry.read().toMap();
				Map<String,Object> deps = (Map<String,Object>) config.get("dependencies");
				System.out.println("DEPENDENCIES: " + deps);
				resolveDependencies(deps);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return Result.SUCCESS;
	}

	private void resolveDependencies(Map<String,Object> deps) {
		// Ensure registry directory exists
		File registryDir = new File(REGISTRY_DIR);
		if(registryDir.exists() || registryDir.mkdirs()) {
			for(String dep : deps.keySet()) {
				String version = (String) deps.get(dep);
				File depDir = new File(REGISTRY_DIR + File.separator + dep + "-" + version);
				if(!depDir.exists()) {
					System.out.println("Making working directory: " + depDir.getPath());
					depDir.mkdirs();
				}
			}
		} else {
			System.out.println("Unable to create directory " + REGISTRY_DIR);
		}
	}
}
