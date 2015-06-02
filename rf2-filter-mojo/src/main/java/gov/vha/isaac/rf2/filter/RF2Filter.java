/**
 * Copyright Notice
 *
 * This is a work of the U.S. Government and is not subject to copyright
 * protection in the United States. Foreign copyrights may apply.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gov.vha.isaac.rf2.filter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import au.com.bytecode.opencsv.CSVParser;
import au.com.bytecode.opencsv.CSVReader;

/**
 * 
 * Loader code to convert Loinc into the workbench.
 * 
 * Paths are typically controlled by maven, however, the main() method has paths configured so that they
 * match what maven does for test purposes.
 */
@Mojo(name = "rf2-filter", defaultPhase = LifecyclePhase.PROCESS_SOURCES)
public class RF2Filter extends AbstractMojo
{

	/**
	 * Location to write the output file
	 */
	@Parameter(required = true, defaultValue = "${project.build.directory}") 
	protected File outputDirectory;

	/**
	 * Location of the input source file(s). May be a file or a directory, depending on the specific loader.
	 * Usually a directory.
	 */
	@Parameter(required = true) 
	protected File inputDirectory;

	/**
	 * Loader version number
	 */
	@Parameter(required = true, defaultValue = "${rf2-filter.version}") 
	protected String converterVersion;

	/**
	 * Converter result version number
	 */
	@Parameter(required = true, defaultValue = "${project.version}") 
	protected String converterResultVersion;
	
	/**
	 * The 7 digit, numeric namespace identifier.  Optional - if not provided, 
	 * only filters on module.
	 */
	@Parameter(required = false) 
	protected Integer namespace;
	
	/**
	 * The numeric module identifier.  Optional - if not provided, only filters on namespace. 
	 */
	@Parameter(required = false) 
	protected Long module;
	
	private String namespaceString_;
	private String moduleString_;
	StringBuilder summary_ = new StringBuilder();

	@Override
	public void execute() throws MojoExecutionException
	{
		if (!inputDirectory.exists() || !inputDirectory.isDirectory())
		{
			throw new MojoExecutionException("Path doesn't exist or isn't a folder: " + inputDirectory);
		}
		
		if (module == null && namespace ==  null)
		{
			throw new MojoExecutionException("You must provide a module or namespace for filtering");
		}
		
		if (module != null)
		{
			moduleString_ = module + "";
		}
		
		if (namespace != null)
		{
			namespaceString_ = namespace + "";
			if (namespaceString_.length() != 7)
			{
				throw new MojoExecutionException("Namespace identifiers must be 7 digits long");
			}
		}
		
		outputDirectory.mkdirs();
		File temp = new File(outputDirectory, inputDirectory.getName());
		temp.mkdirs();
		
		Path source = inputDirectory.toPath();
		Path target = temp.toPath();
		
		getLog().info("Reading from " + inputDirectory.getAbsolutePath());
		getLog().info("Writing to " + outputDirectory.getAbsolutePath());
		
		summary_.append("This content was filtered by an RF2 filter tool.  The parameters were namespace: " + namespaceString_ + " module: " + moduleString_ 
				+ " software version: " + converterVersion);
		summary_.append("\r\n\r\n");
		
		try
		{
			Files.walkFileTree(source, new SimpleFileVisitor<Path>()
			{
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
				{
					Path targetdir = target.resolve(source.relativize(dir));
					try
					{
						//this just creates the sub-directory in the target
						Files.copy(dir, targetdir);
					}
					catch (FileAlreadyExistsException e)
					{
						if (!Files.isDirectory(targetdir))
							throw e;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
				{
					handleFile(file, target.resolve(source.relativize(file)));
					return FileVisitResult.CONTINUE;
				}
			});
			
			Files.write(new File(temp, "FilterInfo.txt").toPath(), summary_.toString().getBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("Failure", e);
		}

		getLog().info("Filter Complete");

	}

	private void handleFile(Path inputFile, Path outputFile) throws IOException
	{
		boolean justCopy = true;
		
		if (inputFile.toFile().getName().toLowerCase().endsWith(".txt"))
		{
			justCopy = false;
			//Filter the file
			BufferedReader fileReader = new BufferedReader(new InputStreamReader(new BOMInputStream(new FileInputStream(inputFile.toFile())), "UTF-8"));
			BufferedWriter fileWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile.toFile()), "UTF-8"));
			PipedOutputStream pos = new PipedOutputStream();
			PipedInputStream pis = new PipedInputStream(pos);
			CSVReader csvReader = new CSVReader(new InputStreamReader(pis), '\t', CSVParser.NULL_CHARACTER);  //don't look for quotes, the data is bad, and has floating instances of '"' all by itself
	
			boolean firstLine = true;
			String line = null;
			long kept = 0;
			long skipped = 0;
			long total = 0;
			
			int moduleColumn = -1;
			int warningCount = 0;
			
			
			while ((line = fileReader.readLine()) != null)
			{
				total++;
				//Write this line into the CSV parser
				pos.write(line.getBytes());
				pos.write("\r\n".getBytes());
				pos.flush();
				String[] fields = csvReader.readNext();
	
				boolean fieldsContainDesiredNamespace = fieldsContainDesiredNamespace(fields);
				boolean correctModule = (moduleColumn >= 0 && moduleString_ != null && fields[moduleColumn].equals(moduleString_) ? true : false);
				
				//sanity check
				if (warningCount < 10)
				{
					if (moduleString_ != null && namespaceString_ != null)
					{
						if (fieldsContainDesiredNamespace != correctModule)
						{
							getLog().warn("Found requested namespace on a line that doesn't contain the module: " + line);
							warningCount++;
						}
					}
				}
				
				if (firstLine || fieldsContainDesiredNamespace || correctModule)
				{
					kept++;
					fileWriter.write(line);
					fileWriter.write("\r\n");
				}
				else
				{
					//don't write line
					skipped++;
				}
				
				if (firstLine)
				{
					
					log("Filtering file " + inputDirectory.toPath().relativize(inputFile).toString());
					firstLine = false;
					if (fields.length < 2)
					{
						log("txt file doesn't look like a data file - abort and just copy.");
						justCopy = true;
						break;
					}
					for (int i = 0; i < fields.length; i++)
					{
						if (fields[i].equals("moduleId"))
						{
							moduleColumn = i;
							break;
						}
					}
				}
			}
	
			fileReader.close();
			csvReader.close();
			fileWriter.close();
			
			if (!justCopy)
			{
				log("Kept " + kept + " Skipped " + skipped + " out of " + total + " lines in " + inputDirectory.toPath().relativize(inputFile).toString());
			}
		}
		
		if (justCopy)
		{
			//Just copy the file
			Files.copy(inputFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
			log("Copied file " + inputDirectory.toPath().relativize(inputFile).toString());
		}
	}
	
	private boolean fieldsContainDesiredNamespace(String[] fields)
	{
		if (namespaceString_ == null)
		{
			return true;
		}
		for (String field : fields)
		{
			//must be a numeric
			if (onlyContainsDigits(field))
			{
				//must contain the 7 digit namepace, 3 digits in from the right side (per the SCT spec)
				if (field.length() >= 10)
				{
					String temp = field.substring(0, field.length() - 3);
					if (temp.endsWith(namespaceString_))
					{
						return true;
					}
				}
			}
		}
		//No field contained the namespace
		return false;
	}
	
	/**
	 * Long.parseLong is terribly slow....
	 */
	private boolean onlyContainsDigits(String str)
	{
		if (str == null)
		{
			return false;
		}
		int length = str.length();
		if (length == 0)
		{
			return false;
		}
		for (int i = 0; i < length; i++)
		{
			char c = str.charAt(i);
			if (c <= '/' || c >= ':')
			{
				return false;
			}
		}
		return true;
	}
	
	private void log(String message)
	{
		summary_.append(message);
		summary_.append("\r\n");
		getLog().info(message);
	}

	/**
	 * Used for debug. Sets up the same paths that maven would use.... allow the code to be run standalone.
	 */
	public static void main(String[] args) throws Exception
	{
		RF2Filter rf2Filter = new RF2Filter();
		rf2Filter.outputDirectory = new File("../rf2-filter-rf2/target/");
		rf2Filter.inputDirectory = new File("/mnt/STORAGE/scratch/SnomedCT_RF2Release_US1000124_20150301/");
		rf2Filter.namespace = 1000124;  //us extension
		rf2Filter.module = 731000124108l;  //us extension
		rf2Filter.converterResultVersion = "foo";
		rf2Filter.converterVersion = "foo";
		rf2Filter.execute();
	}
}
