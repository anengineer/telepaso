class TelePaso {
   // Configuration variables
   final static host = "localhost"
   final static port = 8080

   private static recorded_url  = "http://${host}:${port}/mythweb/tv/recorded"
   private static status_url    = "http://${host}:${port}/mythweb/status"

   // Object members
   //
   def tv_programs = []

   static { // static initializer
      def logger = new MyLogger( "telepaso.log")

      String.metaClass.lastWord = { -> delegate.tokenize("/")[-1] }
      String.metaClass.log      = { -> logger << delegate }

      String.metaClass.wget = { -> 
         new File( delegate.lastWord() ).delete()

//         def cmd = "wget -t 4 -c ${delegate}"
         def cmd = "wget -c -t 32  ${delegate}"
         logger << cmd
         def proc = cmd.execute()
         proc.consumeProcessOutput( System.out, System.err)
         proc.waitFor()
      }

      String.metaClass.stream = { c ->
         delegate.wget()
         new File( delegate.lastWord()).eachLine { c.call( it ) }
      }
   } // static initializer

   static void main( args){
      while( true ){
         new TelePaso().run()
         sleep( 10*60*1000)
      }
   } // main

   void run(){
      create_tv_programs()
      check_converting()

      def last_id = get_last_id()
      tv_programs.findAll{ it.id() > last_id }.sort{ it.id() }.each { 

         if ( it.in_recording == true ){ 
            println "${it} is still recording or converting, skipped."
         } else {
            it.download().rename().mark() 
         }
      }
   } // run

   void create_tv_programs(){
      tv_programs = []
      def tv_program = new TVProgram( host, port ) // dummy

      recorded_url.stream {
         if ( it =~ /file = new Object/ ){
            tv_program = new TVProgram( host, port )
            tv_programs << tv_program
         }
 
         // <a href="/mythweb/tv/detail/1012/1322100420">Still Recording: Edit</a>
         if ( it =~ /Still Recording:/ ){
            parse_in_recording( it )
         }
 
         tv_program.check( it, "title"     , /file\.title\s*=\s*'(.+)';/      )
         tv_program.check( it, "chanid"    , /file\.chanid\s*=\s*'(\d+)';/    )
         tv_program.check( it, "id"        , /file\.starttime\s*=\s*'(\d+)';/ )
         tv_program.check( it, "size"      , /file\.size\s*=\s*'(\d+)';/      )
         tv_program.check( it, "filename"  , /file\.filename\s*=\s*'.+\/(.+)';/      ) // '
      }
   } // create_tv_programs

   void parse_in_recording( line){
      def matcher= line =~ /<a href="(.+)">/ 
      if ( matcher.size() ){
         def url= matcher[0][1]
         def id = url.lastWord()
         tv_programs*.check_recoding( id )
      }
   } // parse_in_recording

   void check_converting(){
      tv_programs*.parseFileName() 

      status_url.stream {
         tv_programs*.check_converting( it )
      }
   } // check_converting

   int get_last_id(){
     def fin = new File( "telepaso.id" )
     if ( ! fin.exists() ) return 0

     fin.text.toInteger()
   } // get_last_id

} // TelePaso

class TVProgram {
   private String  host, title, id, chanid, size, filename, rec_time
   private int     port
   private boolean in_recording= false
   private def     in_converting_pat      

   TVProgram( host, port ){
      this.host   = host
      this.port   = port
   } // TVProgram

   int id() { id.toInteger() }

   void check( line, prop, pattern){
      def matcher = line =~ pattern
      if ( matcher.size() ){
         def value = matcher[0][1]
         setProperty( prop, value )
      }
   } // check

   String toString(){
      "${chanid} ${id} ${title} ${filename} ${rec_time} ${size} -> ${getIdentity()}"
   } // toString

   String getIdentity(){
      "ch${filename[2..-5]}-${id}.nuv"
   } // getIdentity

   def download(){
      "http://${host}:${port}/mythweb/pl/stream/${chanid}/${id}".wget()

      return this
   } // download

   def rename(){
      def name = getIdentity()
      new File( id).renameTo( name)
      "${id} -> ${name}".log()

      return this
   } // rename

   void mark(){
      new File( "telepaso.id").withWriter { it << id }
   } // mark

   void parseFileName(){
      in_converting_pat = /(jobqueued|jobrunning)/
      // change it to something like "11/23 11:00 PM"
      def matcher = filename =~ /^\d+_20\d\d(\d\d)(\d\d)(\d\d)(\d\d)\d\d\.nuv/
      if ( matcher.size() ){
         def ( mon, dd, hh, min ) = matcher[0][ 1..4 ] as int[]
     
         def ampm= "AM"
         ( hh, ampm ) = hh >= 12 ? [ hh - 12, "PM" ] : [ hh, "AM" ]
         rec_time= sprintf( "%d/%d %d:%02d %s", mon, dd, hh, min, ampm)
         in_converting_pat = /${rec_time}.+(jobqueued|jobrunning)/   
      }
   } // parseFileName

   void check_recoding( id ){
      if ( this.id == id ){
         this.in_recording = true
         println "Still recording ${getIdentity()}"
      }
   } // check_recoding

   void check_converting( line ){
      if ( line =~ this.in_converting_pat ){
         this.in_recording = true
         println "Still converting ${getIdentity()}" 
      }
   } // check_converting
} // TVProgram

class MyLogger {
   private def fout = null

   MyLogger( logname ){
      fout = new File( logname )
   } // MyLogger

   void leftShift( line ){
      def s = new Date().format("yyyy-MM-dd hh:mm:ss") +" ${line}\n"
      println s
      fout << s
   } // leftShift
} //  MyLogger
