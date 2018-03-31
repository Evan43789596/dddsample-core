package se.citerus.dddsample.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static se.citerus.dddsample.application.util.DateTestUtil.toDate;
import static se.citerus.dddsample.domain.model.cargo.RoutingStatus.MISROUTED;
import static se.citerus.dddsample.domain.model.cargo.RoutingStatus.NOT_ROUTED;
import static se.citerus.dddsample.domain.model.cargo.RoutingStatus.ROUTED;
import static se.citerus.dddsample.domain.model.cargo.TransportStatus.CLAIMED;
import static se.citerus.dddsample.domain.model.cargo.TransportStatus.IN_PORT;
import static se.citerus.dddsample.domain.model.cargo.TransportStatus.NOT_RECEIVED;
import static se.citerus.dddsample.domain.model.cargo.TransportStatus.ONBOARD_CARRIER;
import static se.citerus.dddsample.domain.model.handling.HandlingEvent.Type.CLAIM;
import static se.citerus.dddsample.domain.model.handling.HandlingEvent.Type.LOAD;
import static se.citerus.dddsample.domain.model.handling.HandlingEvent.Type.RECEIVE;
import static se.citerus.dddsample.domain.model.handling.HandlingEvent.Type.UNLOAD;
import static se.citerus.dddsample.domain.model.location.SampleLocations.CHICAGO;
import static se.citerus.dddsample.domain.model.location.SampleLocations.HAMBURG;
import static se.citerus.dddsample.domain.model.location.SampleLocations.HONGKONG;
import static se.citerus.dddsample.domain.model.location.SampleLocations.NEWYORK;
import static se.citerus.dddsample.domain.model.location.SampleLocations.STOCKHOLM;
import static se.citerus.dddsample.domain.model.location.SampleLocations.TOKYO;
import static se.citerus.dddsample.domain.model.voyage.SampleVoyages.v100;
import static se.citerus.dddsample.domain.model.voyage.SampleVoyages.v200;
import static se.citerus.dddsample.domain.model.voyage.SampleVoyages.v300;
import static se.citerus.dddsample.domain.model.voyage.SampleVoyages.v400;
import static se.citerus.dddsample.domain.model.voyage.Voyage.NONE;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import se.citerus.dddsample.application.ApplicationEvents;
import se.citerus.dddsample.application.BookingService;
import se.citerus.dddsample.application.CargoInspectionService;
import se.citerus.dddsample.application.HandlingEventService;
import se.citerus.dddsample.application.impl.BookingServiceImpl;
import se.citerus.dddsample.application.impl.CargoInspectionServiceImpl;
import se.citerus.dddsample.application.impl.HandlingEventServiceImpl;
import se.citerus.dddsample.domain.model.cargo.Cargo;
import se.citerus.dddsample.domain.model.cargo.CargoRepository;
import se.citerus.dddsample.domain.model.cargo.HandlingActivity;
import se.citerus.dddsample.domain.model.cargo.Itinerary;
import se.citerus.dddsample.domain.model.cargo.Leg;
import se.citerus.dddsample.domain.model.cargo.RouteSpecification;
import se.citerus.dddsample.domain.model.cargo.TrackingId;
import se.citerus.dddsample.domain.model.handling.CannotCreateHandlingEventException;
import se.citerus.dddsample.domain.model.handling.HandlingEventFactory;
import se.citerus.dddsample.domain.model.handling.HandlingEventRepository;
import se.citerus.dddsample.domain.model.location.Location;
import se.citerus.dddsample.domain.model.location.LocationRepository;
import se.citerus.dddsample.domain.model.location.UnLocode;
import se.citerus.dddsample.domain.model.voyage.VoyageNumber;
import se.citerus.dddsample.domain.model.voyage.VoyageRepository;
import se.citerus.dddsample.domain.service.RoutingService;
import se.citerus.dddsample.infrastructure.messaging.stub.SynchronousApplicationEventsStub;
import se.citerus.dddsample.infrastructure.persistence.inmemory.CargoRepositoryInMem;
import se.citerus.dddsample.infrastructure.persistence.inmemory.HandlingEventRepositoryInMem;
import se.citerus.dddsample.infrastructure.persistence.inmemory.LocationRepositoryInMem;
import se.citerus.dddsample.infrastructure.persistence.inmemory.VoyageRepositoryInMem;

public class CargoLifecycleScenarioTest {

  /**
   * 仓库实现是基础架构层的一部分,
   * 在本测试中会被内存中替代物所替代
   */
  HandlingEventRepository handlingEventRepository;
  CargoRepository cargoRepository;
  LocationRepository locationRepository;
  VoyageRepository voyageRepository;

  /**
   * 这个接口是应用程序层的一部分，
   * 定义了一些应用程序执行期间发生的事件。
   * 它用于消息驱动并通过JMS去实施。
   *
   * 在本测试中，它通过同步调用去替代
   *
   */
  ApplicationEvents applicationEvents;

  /**
   * 这三个组件都属于应用程序层，并与该应用程序的用例相对应。
   * “真实”的实现被用于整个生命周期的测试，但通过基础设施桩去操控
   *
   */
  BookingService bookingService;
  HandlingEventService handlingEventService;
  CargoInspectionService cargoInspectionService;

  /**
   * 该工厂是处理聚合的一部分，属于领域层。
   * 与应用层的组件相类似，这里也使用了“真实”实现，通过基础设施桩去操控。
   *
   */
  HandlingEventFactory handlingEventFactory;

  /**
   * 这是一个域服务接口，它的实现是基础设施层的一部分(远程调用外部系统)。
   * 它在本测试通过桩代替。
   */
  RoutingService routingService;

  @Test
  public void testCargoFromHongkongToStockholm() throws Exception {
    /* 测试设置：货物应从香港运往斯德哥尔摩，
       它应该在不超过两周时间内到达。 */
    Location origin = HONGKONG;
    Location destination = STOCKHOLM;
    Date arrivalDeadline = toDate("2009-03-18");

    /* 用例1：预订

       货被预订，并且这唯一的跟踪编号被分配给货物。 */
    TrackingId trackingId = bookingService.bookNewCargo(
      origin.unLocode(), destination.unLocode(), arrivalDeadline
    );

    /* 跟踪ID可用于查找仓库中的货物。
       重要: 货物和域模型负责确定
        货物的状态，是否在正确的轨道上等等。
        这是核心领域的逻辑。

       跟踪货物基本上等同于呈现从货物提取的信息
        以合适的方式聚合。 */
    Cargo cargo = cargoRepository.find(trackingId);
    assertThat(cargo).isNotNull();
    assertThat(cargo.delivery().transportStatus()).isEqualTo(NOT_RECEIVED);
    assertThat(cargo.delivery().routingStatus()).isEqualTo(NOT_ROUTED);
    assertThat(cargo.delivery().isMisdirected()).isFalse();
    assertThat(cargo.delivery().estimatedTimeOfArrival()).isNull();
    assertThat(cargo.delivery().nextExpectedActivity()).isNull();

    /* Use case 2: routing

       A number of possible routes for this cargo is requested and may be
       presented to the customer in some way for him/her to choose from.
       Selection could be affected by things like price and time of delivery,
       but this test simply uses an arbitrary selection to mimic that process.

       The cargo is then assigned to the selected route, described by an itinerary. */
    List<Itinerary> itineraries = bookingService.requestPossibleRoutesForCargo(trackingId);
    Itinerary itinerary = selectPreferedItinerary(itineraries);
    cargo.assignToRoute(itinerary);

    assertThat(cargo.delivery().transportStatus()).isEqualTo(NOT_RECEIVED);
    assertThat(cargo.delivery().routingStatus()).isEqualTo(ROUTED);
    assertThat(cargo.delivery().estimatedTimeOfArrival()).isNotNull();
    assertThat(cargo.delivery().nextExpectedActivity()).isEqualTo(new HandlingActivity(RECEIVE, HONGKONG));

    /*
      Use case 3: handling

      A handling event registration attempt will be formed from parsing
      the data coming in as a handling report either via
      the web service interface or as an uploaded CSV file.

      The handling event factory tries to create a HandlingEvent from the attempt,
      and if the factory decides that this is a plausible handling event, it is stored.
      If the attempt is invalid, for example if no cargo exists for the specfied tracking id,
      the attempt is rejected.

      Handling begins: cargo is received in Hongkong.
      */
    handlingEventService.registerHandlingEvent(
      toDate("2009-03-01"), trackingId, null, HONGKONG.unLocode(), RECEIVE
    );

    assertThat(cargo.delivery().transportStatus()).isEqualTo(IN_PORT);
    assertThat(cargo.delivery().lastKnownLocation()).isEqualTo(HONGKONG);
    
    // Next event: Load onto voyage CM003 in Hongkong
    handlingEventService.registerHandlingEvent(
      toDate("2009-03-03"), trackingId, v100.voyageNumber(), HONGKONG.unLocode(), LOAD
    );

    // Check current state - should be ok
    assertThat(cargo.delivery().currentVoyage()).isEqualTo(v100);
    assertThat(cargo.delivery().lastKnownLocation()).isEqualTo(HONGKONG);
    assertThat(cargo.delivery().transportStatus()).isEqualTo(ONBOARD_CARRIER);
    assertThat(cargo.delivery().isMisdirected()).isFalse();
    assertThat(cargo.delivery().nextExpectedActivity()).isEqualTo(new HandlingActivity(UNLOAD, NEWYORK, v100));


    /*
      Here's an attempt to register a handling event that's not valid
      because there is no voyage with the specified voyage number,
      and there's no location with the specified UN Locode either.

      This attempt will be rejected and will not affect the cargo delivery in any way.
     */
    final VoyageNumber noSuchVoyageNumber = new VoyageNumber("XX000");
    final UnLocode noSuchUnLocode = new UnLocode("ZZZZZ");
    try {
      handlingEventService.registerHandlingEvent(
      toDate("2009-03-05"), trackingId, noSuchVoyageNumber, noSuchUnLocode, LOAD
      );
      fail("Should not be able to register a handling event with invalid location and voyage");
    } catch (CannotCreateHandlingEventException expected) {
    }


    // Cargo is now (incorrectly) unloaded in Tokyo
    handlingEventService.registerHandlingEvent(
      toDate("2009-03-05"), trackingId, v100.voyageNumber(), TOKYO.unLocode(), UNLOAD
    );

    // Check current state - cargo is misdirected!
    assertThat(cargo.delivery().currentVoyage()).isEqualTo(NONE);
    assertThat(cargo.delivery().lastKnownLocation()).isEqualTo(TOKYO);
    assertThat(cargo.delivery().transportStatus()).isEqualTo(IN_PORT);
    assertThat(cargo.delivery().isMisdirected()).isTrue();
    assertThat(cargo.delivery().nextExpectedActivity()).isNull();


    // -- Cargo needs to be rerouted --

    // TODO cleaner reroute from "earliest location from where the new route originates"

    // Specify a new route, this time from Tokyo (where it was incorrectly unloaded) to Stockholm
    RouteSpecification fromTokyo = new RouteSpecification(TOKYO, STOCKHOLM, arrivalDeadline);
    cargo.specifyNewRoute(fromTokyo);

    // The old itinerary does not satisfy the new specification
    assertThat(cargo.delivery().routingStatus()).isEqualTo(MISROUTED);
    assertThat(cargo.delivery().nextExpectedActivity()).isNull();

    // Repeat procedure of selecting one out of a number of possible routes satisfying the route spec
    List<Itinerary> newItineraries = bookingService.requestPossibleRoutesForCargo(cargo.trackingId());
    Itinerary newItinerary = selectPreferedItinerary(newItineraries);
    cargo.assignToRoute(newItinerary);

    // New itinerary should satisfy new route
    assertThat(cargo.delivery().routingStatus()).isEqualTo(ROUTED);

    // TODO we can't handle the face that after a reroute, the cargo isn't misdirected anymore
    //assertThat(cargo.isMisdirected()).isFalse();
    //assertThat(, cargo.nextExpectedActivity()).isEqualTo(new HandlingActivity(LOAD, TOKYO));


    // -- Cargo has been rerouted, shipping continues --


    // Load in Tokyo
    handlingEventService.registerHandlingEvent(
      toDate("2009-03-08"), trackingId, v300.voyageNumber(), TOKYO.unLocode(), LOAD
    );

    // Check current state - should be ok
    assertThat(cargo.delivery().currentVoyage()).isEqualTo(v300);
    assertThat(cargo.delivery().lastKnownLocation()).isEqualTo(TOKYO);
    assertThat(cargo.delivery().transportStatus()).isEqualTo(ONBOARD_CARRIER);
    assertThat(cargo.delivery().isMisdirected()).isFalse();
    assertThat(cargo.delivery().nextExpectedActivity()).isEqualTo(new HandlingActivity(UNLOAD, HAMBURG, v300));

    // Unload in Hamburg
    handlingEventService.registerHandlingEvent(
      toDate("2009-03-12"), trackingId, v300.voyageNumber(), HAMBURG.unLocode(), UNLOAD
    );

    // Check current state - should be ok
    assertThat(cargo.delivery().currentVoyage()).isEqualTo(NONE);
    assertThat(cargo.delivery().lastKnownLocation()).isEqualTo(HAMBURG);
    assertThat(cargo.delivery().transportStatus()).isEqualTo(IN_PORT);
    assertThat(cargo.delivery().isMisdirected()).isFalse();
    assertThat(cargo.delivery().nextExpectedActivity()).isEqualTo(new HandlingActivity(LOAD, HAMBURG, v400));


    // Load in Hamburg
    handlingEventService.registerHandlingEvent(
      toDate("2009-03-14"), trackingId, v400.voyageNumber(), HAMBURG.unLocode(), LOAD
    );

    // Check current state - should be ok
    assertThat(cargo.delivery().currentVoyage()).isEqualTo(v400);
    assertThat(cargo.delivery().lastKnownLocation()).isEqualTo(HAMBURG);
    assertThat(cargo.delivery().transportStatus()).isEqualTo(ONBOARD_CARRIER);
    assertThat(cargo.delivery().isMisdirected()).isFalse();
    assertThat(cargo.delivery().nextExpectedActivity()).isEqualTo(new HandlingActivity(UNLOAD, STOCKHOLM, v400));


    // Unload in Stockholm
    handlingEventService.registerHandlingEvent(
      toDate("2009-03-15"), trackingId, v400.voyageNumber(), STOCKHOLM.unLocode(), UNLOAD
    );

    // Check current state - should be ok
    assertThat(cargo.delivery().currentVoyage()).isEqualTo(NONE);
    assertThat(cargo.delivery().lastKnownLocation()).isEqualTo(STOCKHOLM);
    assertThat(cargo.delivery().transportStatus()).isEqualTo(IN_PORT);
    assertThat(cargo.delivery().isMisdirected()).isFalse();
    assertThat(cargo.delivery().nextExpectedActivity()).isEqualTo(new HandlingActivity(CLAIM, STOCKHOLM));

    // Finally, cargo is claimed in Stockholm. This ends the cargo lifecycle from our perspective.
    handlingEventService.registerHandlingEvent(
      toDate("2009-03-16"), trackingId, null, STOCKHOLM.unLocode(), CLAIM
    );

    // Check current state - should be ok
    assertThat(cargo.delivery().currentVoyage()).isEqualTo(NONE);
    assertThat(cargo.delivery().lastKnownLocation()).isEqualTo(STOCKHOLM);
    assertThat(cargo.delivery().transportStatus()).isEqualTo(CLAIMED);
    assertThat(cargo.delivery().isMisdirected()).isFalse();
    assertThat(cargo.delivery().nextExpectedActivity()).isNull();
  }


  /*
  * Utility stubs below.
  */

  private Itinerary selectPreferedItinerary(List<Itinerary> itineraries) {
    return itineraries.get(0);
  }

  @Before
  public void setUp() {
    routingService = new RoutingService() {
      public List<Itinerary> fetchRoutesForSpecification(RouteSpecification routeSpecification) {
        if (routeSpecification.origin().equals(HONGKONG)) {
          // Hongkong - NYC - Chicago - Stockholm, initial routing
          return Arrays.asList(
            new Itinerary(Arrays.asList(
              new Leg(v100, HONGKONG, NEWYORK, toDate("2009-03-03"), toDate("2009-03-09")),
              new Leg(v200, NEWYORK, CHICAGO, toDate("2009-03-10"), toDate("2009-03-14")),
              new Leg(v200, CHICAGO, STOCKHOLM, toDate("2009-03-07"), toDate("2009-03-11"))
            ))
          );
        } else {
          // Tokyo - Hamburg - Stockholm, rerouting misdirected cargo from Tokyo 
          return Arrays.asList(
            new Itinerary(Arrays.asList(
              new Leg(v300, TOKYO, HAMBURG, toDate("2009-03-08"), toDate("2009-03-12")),
              new Leg(v400, HAMBURG, STOCKHOLM, toDate("2009-03-14"), toDate("2009-03-15"))
            ))
          );
        }
      }
    };


    applicationEvents = new SynchronousApplicationEventsStub();

    // In-memory implementations of the repositories
    handlingEventRepository = new HandlingEventRepositoryInMem();
    cargoRepository = new CargoRepositoryInMem();
    locationRepository = new LocationRepositoryInMem();
    voyageRepository = new VoyageRepositoryInMem();

    // Actual factories and application services, wired with stubbed or in-memory infrastructure
    handlingEventFactory = new HandlingEventFactory(cargoRepository, voyageRepository, locationRepository);

    cargoInspectionService = new CargoInspectionServiceImpl(applicationEvents, cargoRepository, handlingEventRepository);
    handlingEventService = new HandlingEventServiceImpl(handlingEventRepository, applicationEvents, handlingEventFactory);
    bookingService = new BookingServiceImpl(cargoRepository, locationRepository, routingService);

    // Circular dependency when doing synchrounous calls
    ((SynchronousApplicationEventsStub) applicationEvents).setCargoInspectionService(cargoInspectionService);
  }

}
