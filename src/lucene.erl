%%%-------------------------------------------------------------------
%%% @author Fernando Benavides <elbrujohalcon@inaka.net>
%%% @doc Lucene Interface
%%% @end
%%%-------------------------------------------------------------------
-module(lucene).
-author('elbrujohalcon@inaka.net').

%%NOTE: We let java server run for as long as it needs to run.
%%      Even if we choose a smaller timeout, it will run for a longer time anyway if it needs to.
-define(CALL_TIMEOUT, infinity).
-define(LUCENE_SERVER, {lucene_server, java_node()}).

-behavior(gen_server).

-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2, code_change/3]).
-export([start_link/0]).

-export([process/0]).
-export([add/1, del/1, clear/0, stop/0]).
-export([match/2, match/3, continue/2, continue/3]).

-record(state, {java_port :: port(),
                java_node :: atom()}).
-opaque state() :: #state{}.

-type page_token() :: binary().
-export_type([page_token/0]).

-type metadata() :: [{page_token, page_token()} | {total_hits, non_neg_integer()} | {first_hit, non_neg_integer()}].

-type doc() :: [{atom(), string()},...].
-export_type([doc/0]).

%%-------------------------------------------------------------------
%% PUBLIC API
%%-------------------------------------------------------------------
%% @doc  Starts a new monitor
-spec start_link() -> {ok, pid()} | {error, term()}.
start_link() -> gen_server:start_link(?MODULE, [], []).

%% @doc Returns the pid of the lucene process
-spec process() -> pid().
process() -> gen_server:call(?LUCENE_SERVER, {pid}, ?CALL_TIMEOUT).

%% @equiv match(Query, PageSize, infinity)
-spec match(string(), pos_integer()) -> {[doc()], metadata()} | '$end_of_table'.
match(Query, PageSize) -> match(Query, PageSize, ?CALL_TIMEOUT).

%% @doc Runs a query against the lucene server
-spec match(string(), pos_integer(), infinity | pos_integer()) -> {[doc()], metadata()} | '$end_of_table'.
match(Query, PageSize, Timeout) -> make_call({match, Query, PageSize}, Timeout).

%% @equiv continue(PageToken, PageSize, infinity)
-spec continue(page_token(), pos_integer()) -> {[string()], metadata()} | '$end_of_table'.
continue(PageToken, PageSize) -> continue(PageToken, PageSize, ?CALL_TIMEOUT).

%% @doc Continues a Query where it was left
-spec continue(page_token(), pos_integer(), infinity | pos_integer()) -> {[string()], metadata()} | '$end_of_table'.
continue(PageToken, PageSize, Timeout) -> make_call({continue, PageToken, PageSize}, Timeout).

%% @doc Stops the java process
-spec stop() -> ok.
stop() -> gen_server:cast(?LUCENE_SERVER, {stop}).

%% @doc Clears the whole index
%%      USE WITH CAUTION
-spec clear() -> ok.
clear() -> gen_server:cast(?LUCENE_SERVER, {clear}).

%% @doc Registers a list of docs
-spec add([doc()]) -> ok.
add(Docs) -> gen_server:cast(?LUCENE_SERVER, {add, Docs}).

%% @doc Removes docs matching a certain query
-spec del(string()) -> ok.
del(Query) -> gen_server:cast(?LUCENE_SERVER, {del, Query}).

%%-------------------------------------------------------------------
%% GEN_SERVER API
%%-------------------------------------------------------------------
-spec init([]) -> {ok, state()}.
init([]) ->
  case os:find_executable("java") of
    [] ->
      _ = lager:critical("You need to have java installed.", []),
      throw({stop, java_missing});
    Java ->
      JavaNode = java_node(),
      JPriv =
        case code:priv_dir(jinterface) of
          {error, bad_name} ->
            lager:info("Couldn't find priv dir for lucene_server, using ./priv"),
            "./priv";
          JPrivDir -> filename:absname(JPrivDir)
        end,
      Priv =
        case code:priv_dir(lucene_server) of
          {error, bad_name} ->
            lager:info("Couldn't find priv dir for lucene_server, using ./priv"),
            "./priv";
          PrivDir -> filename:absname(PrivDir)
        end,
      Port =
        erlang:open_port({spawn_executable, Java},
                         [{line,1000}, stderr_to_stdout,
                          {args, ["-classpath",
                                  string:join(
                                    [Priv ++ "/lucene-server.jar",
                                     Priv ++ "/lucene-core-3.6.0.jar",
                                     JPriv ++ "/OtpErlang.jar"], ":"),
                                  "com.tigertext.lucene.LuceneNode",
                                  JavaNode, erlang:get_cookie()]}]),
      {ok, #state{java_port = Port, java_node = JavaNode}}
  end.

-spec handle_call(X, _From, state()) -> {stop, {unexpected_request, X}, {unexpected_request, X}, state()}.
handle_call(X, _From, State) -> {stop, {unexpected_request, X}, {unexpected_request, X}, State}.

-spec handle_info({nodedown, atom()}, state()) -> {stop, nodedown, state()} | {noreply, state()}.
handle_info({nodedown, JavaNode}, State = #state{java_node = JavaNode}) ->
  lager:error("Java node is down!"),
  {stop, nodedown, State};
handle_info({Port, {data, {eol, "READY"}}}, State = #state{java_port = Port}) ->
  _ = lager:info("Java node started"),
  true = link(process()),
  true = erlang:monitor_node(State#state.java_node, true),
  {noreply, State};
handle_info({Port, {data, {eol, JavaLog}}}, State = #state{java_port = Port}) ->
  _ = lager:info("Java Log:~s", [JavaLog]),
  {noreply, State};
handle_info(Info, State) ->
  _ = lager:warning("Unexpected info: ~p", [Info]),
  {noreply, State}.

-spec terminate(_, state()) -> true.
terminate(_Reason, State) -> erlang:port_close(State#state.java_port).

-spec handle_cast(term(), state()) -> {noreply, state()}.
handle_cast(_Msg, State) -> {noreply, State}.
-spec code_change(term(), state(), term()) -> {ok, state()}.
code_change(_OldVersion, State, _Extra) -> {ok, State}.

%%-------------------------------------------------------------------
%% PRIVATE
%%-------------------------------------------------------------------
java_node() ->
  case string:tokens(atom_to_list(node()), "@") of
    [Name, Server] -> list_to_atom(Name ++ "_java@" ++ Server);
    _Node -> throw({bad_node_name, node()})
  end.

make_call(Call, Timeout) ->
  case gen_server:call(?LUCENE_SERVER, Call, Timeout) of
    {ok, Result} -> Result;
    {error, Error} -> throw(Error)
  end.