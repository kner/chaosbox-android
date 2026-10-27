package local.sshcopy;
public class SharedSetupTest {
 static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  String text="[App]\nImageWidth=3000\nFields=Box,Quantity,Device,Alias,Category,Comment,Package\nCategories=Shared\n[Box]\nTitle=First\nImageWidth=1200\n[Box]\nTitle=Second\nFields=Box,,,Tags,Category,Comment\nCategories=Photos\n[Poster]\nPOSTER-COLS=3\n[SSH.Second]\nInstallation=target\n[TextSnippets]\nask=\"hello\n[Box]\nTitle=not-a-box\nworld\"\n";
  AppProfiles config=AppProfiles.parse(text);
  check(config.profiles.size()==2,"Repeated sections stay separate");
  check(config.defaultId.equals("First"),"First Box is default");
  check(config.find("First").imageWidth==1200 && config.find("Second").imageWidth==3000,"Inherited per-box ImageWidth");
  check(config.find("First").categories.contains("Shared"),"Inherited categories");
  check(!config.find("Second").visible(1),"Hidden field positions");
  String changed=AppProfiles.withCategory(text,"Second","New");
  check(AppProfiles.parse(changed).find("Second").categories.contains("New"),"Update selected Box");
  check(AppProfiles.parse(changed).find("First").categories.equals(config.find("First").categories),"Other Box unchanged");
  check(AppProfiles.withDefaults(text).equals(text),"Shared format remains intact");
  check(AppProfiles.withCategory(changed,"Second","New").equals(changed),"Category update is idempotent");
  String inherited=AppProfiles.withCategory(text,"First","New");
  check(AppProfiles.parse(inherited).find("First").categories.equals(java.util.List.of("Shared","New")),"Category edit retains inherited values");
  String german="[App]\nFelder=Box,Quantity,Device,Alias,Category,Comment,Package\nKategorie=Inherited\n[Box]\nTitel=German\n";
  String english=AppProfiles.withDefaults(german);
  check(english.contains("Title=German")&&english.contains("Fields=")&&english.contains("Categories=Inherited"),"German setup keys migrate to English");
  check(AppProfiles.parse(german).find("German")!=null,"German keys remain readable");
  check(AppProfiles.withCategory(english,"German","Added").contains("Categories=Inherited, Added"),"English category writes");
  String old="[App]\nStandard=Photos\n[App.Old]\nTitle=Custom title\n[App.Photos]\nCategories=Photo\n[ImageSize]\nLIMIT=2048\n[SSH]\nInstallation=existing\n";
  String migrated=AppProfiles.withDefaults(old);
  check(!migrated.contains("[App.")&&!migrated.contains("[ImageSize]"),"Legacy entries migrated");
  check(AppProfiles.parse(migrated).defaultId.equals("Photos"),"Legacy default preserved by ordering");
  check(AppProfiles.parse(migrated).find("Old").images.equals("Old/JPG"),"Migration preserves folders");
  check(AppProfiles.parse(migrated).find("Photos").imageWidth==2048,"Legacy image width preserved");
  check(migrated.contains("Installation=existing"),"SSH target preserved");
  check(AppProfiles.withDefaults(migrated).equals(migrated),"Migration is idempotent");
  for(String invalid:new String[]{"[Box]\nTitle=../bad\n","[Box]\nTitle=X\n[Box]\nTitle=x\n","[App]\nImageWidth=0\n[Box]\nTitle=X\n"}) {
   try{AppProfiles.parse(invalid);throw new AssertionError("Invalid setup accepted");}catch(java.io.IOException expected){}
  }
  System.out.println("PASS: shared Box setup, inheritance, category edits, validation and legacy migration");
 }
}
